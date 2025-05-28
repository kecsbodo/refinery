/*
 * SPDX-FileCopyrightText: 2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.semantics.internal.query;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.EcoreUtil2;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.language.semantics.SemanticsUtils;
import tools.refinery.language.semantics.TracedException;
import tools.refinery.language.typesystem.DataExprType;
import tools.refinery.language.typesystem.FixedType;
import tools.refinery.language.typesystem.ProblemTypeAnalyzer;
import tools.refinery.language.utils.ProblemUtil;
import tools.refinery.language.validation.ReferenceCounter;
import tools.refinery.logic.Constraint;
import tools.refinery.logic.dnf.AbstractQueryBuilder;
import tools.refinery.logic.dnf.Dnf;
import tools.refinery.logic.dnf.Query;
import tools.refinery.logic.dnf.RelationalQuery;
import tools.refinery.logic.literal.BooleanLiteral;
import tools.refinery.logic.literal.CallPolarity;
import tools.refinery.logic.literal.ConstantLiteral;
import tools.refinery.logic.literal.Literal;
import tools.refinery.logic.term.NodeVariable;
import tools.refinery.logic.term.Term;
import tools.refinery.logic.term.Variable;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.store.reasoning.ReasoningAdapter;
import tools.refinery.store.reasoning.literal.PartialCheckLiteral;
import tools.refinery.store.reasoning.representation.PartialRelation;

import java.util.*;

public class QueryCompiler {
	@Inject
	private SemanticsUtils semanticsUtils;

	@Inject
	private ReferenceCounter referenceCounter;

	@Inject
	private ProblemTypeAnalyzer problemTypeAnalyzer;

	@Inject
	private Provider<QueryBasedExprToTerm> exprToTermProvider;

	@Inject
	private QueryBasedExprToTerm queryBasedExprToTerm;

	private ProblemTrace problemTrace;

	public void setProblemTrace(ProblemTrace problemTrace) {
		this.problemTrace = problemTrace;
	}

	public RelationalQuery toQuery(String name, PredicateDefinition predicateDefinition) {
		try {
			var problemParameters = predicateDefinition.getParameters();
			int arity = problemParameters.size();
			var parameters = new NodeVariable[arity];
			var parameterMap = HashMap.<tools.refinery.language.model.problem.Variable, Variable>newHashMap(arity);
			var commonLiterals = new ArrayList<Literal>();
			for (int i = 0; i < arity; i++) {
				var problemParameter = problemParameters.get(i);
				var parameter = Variable.of(problemParameter.getName());
				parameters[i] = parameter;
				parameterMap.put(problemParameter, parameter);
				var parameterType = problemParameter.getParameterType();
				if (parameterType != null) {
					var partialType = getPartialRelation(parameterType);
					commonLiterals.add(partialType.call(parameter));
				}
			}
			var builder = Query.builder(name).parameters(parameters);
			for (var body : predicateDefinition.getBodies()) {
				buildConjunction(body, parameterMap, commonLiterals, builder);
			}
			return builder.build();
		} catch (RuntimeException e) {
			throw TracedException.addTrace(predicateDefinition, e);
		}
	}

	void buildConjunction(
			Conjunction body, Map<tools.refinery.language.model.problem.Variable, ? extends Variable> parameterMap,
			List<Literal> commonLiterals, AbstractQueryBuilder<?> builder) {
		try {
			var localScope = extendScope(parameterMap, body.getImplicitVariables());
			// expression (E)list
			var problemLiterals = body.getLiterals();
			// literal list
			var literals = new ArrayList<>(commonLiterals);
			for (var problemLiteral : problemLiterals) {
				toLiteralsTraced(problemLiteral, localScope, literals);
			}
			builder.clause(literals);
		} catch (RuntimeException e) {
			throw TracedException.addTrace(body, e);
		}
	}

	int getNodeId(Node node) {
		return problemTrace.getNodeId(node);
	}

	PartialRelation getPartialRelation(Relation relation) {
		return problemTrace.getPartialRelation(relation);
	}

	private Map<tools.refinery.language.model.problem.Variable, ? extends Variable> extendScope(
			Map<tools.refinery.language.model.problem.Variable, ? extends Variable> existing,
			Collection<? extends tools.refinery.language.model.problem.Variable> newVariables) {
		if (newVariables.isEmpty()) {
			return existing;
		}
		int localScopeSize = existing.size() + newVariables.size();
		var localScope = HashMap.<tools.refinery.language.model.problem.Variable, Variable>newHashMap(localScopeSize);
		localScope.putAll(existing);
		for (var newVariable : newVariables) {
			localScope.put(newVariable, Variable.of(newVariable.getName()));
		}
		return localScope;
	}

	private void toLiteralsTraced(
			Expr expr, Map<tools.refinery.language.model.problem.Variable, ? extends Variable> localScope,
			List<Literal> literals) {
		try {
			toLiterals(expr, localScope, literals);
		} catch (RuntimeException e) {
			throw TracedException.addTrace(expr, e);
		}
	}

	private void toLiterals(
			Expr expr, Map<tools.refinery.language.model.problem.Variable, ? extends Variable> localScope,
			List<Literal> literals) {
		var extractedOuter = ExtractedModalExpr.of(expr);
		var outerModality = extractedOuter.modality();
		switch (extractedOuter.body()) {
		case LogicConstant logicConstant -> {
			switch (logicConstant.getLogicValue()) {
			case TRUE -> literals.add(BooleanLiteral.TRUE);
			case FALSE -> literals.add(BooleanLiteral.FALSE);
			default -> throw new TracedException(logicConstant, "Unsupported literal");
			}
		}
		case Atom atom -> {
			var constraint = getConstraint(atom);
			var argumentList = toArgumentList(atom, atom.getArguments(), localScope, literals);
			literals.add(extractedOuter.modality().wrapConstraint(constraint).call(CallPolarity.POSITIVE,
					argumentList.arguments()));
		}
		case NegationExpr negationExpr -> {
			var body = negationExpr.getBody();
			var extractedInner = ExtractedModalExpr.of(body);
			if (!(extractedInner.body() instanceof Atom atom)) {
				throw new TracedException(extractedInner.body(), "Cannot negate literal");
			}
			var negatedScope = extendScope(localScope, negationExpr.getImplicitVariables());
			var argumentList = toArgumentList(atom, atom.getArguments(), negatedScope, literals);
			var innerModality = extractedInner.modality().merge(outerModality.negate());
			var constraint = getConstraint(atom);
			literals.add(createNegationLiteral(innerModality, constraint, argumentList));
		}
		case ComparisonExpr comparisonExpr -> {
			FixedType rightType = problemTypeAnalyzer.getExpressionType(comparisonExpr.getRight());
			FixedType leftType = problemTypeAnalyzer.getExpressionType(comparisonExpr.getLeft());
			if (!(rightType instanceof DataExprType) && !(leftType instanceof DataExprType)) {
				var argumentList = toArgumentList(comparisonExpr,
						List.of(comparisonExpr.getLeft(), comparisonExpr.getRight()), localScope, literals);
				boolean positive = switch (comparisonExpr.getOp()) {
					case NODE_EQ -> true;
					case NODE_NOT_EQ -> false;
					default -> throw new TracedException(
							comparisonExpr, "Unsupported operator");
				};
				literals.add(createEquivalenceLiteral(outerModality, positive, argumentList));
			}
		}
		default -> {
		}
		}
		var exprToTerm = exprToTermProvider.get();
		exprToTerm.setLiterals(literals);
		exprToTerm.setQueryCompiler(this);
		exprToTerm.setLocalScope(localScope);
		exprToTerm.setProblemTrace(problemTrace);
		var optTerm = exprToTerm.toTerm(expr);
		if (optTerm.isPresent()) {
			// could be an arithmetic operation?
			@SuppressWarnings("unchecked")
			var term = (Term<TruthValue>) optTerm.get();
			literals.add(new PartialCheckLiteral(term));
		} else {
			throw new TracedException(expr, "Cannot interpret expression as Term/Literal");
		}
	}

	private Constraint getConstraint(Atom atom) {
		var relation = atom.getRelation();
		var target = getPartialRelation(relation);
		return atom.isTransitiveClosure() ? getTransitiveWrapper(target) : target;
	}

	private Constraint getTransitiveWrapper(Constraint target) {
		return Query.of(target.name() + "#transitive", (builder, p1, p2) -> builder.clause(
				target.callTransitive(p1, p2)
		)).getDnf();
	}

	private static Literal createNegationLiteral(
			ConcreteModality innerModality, Constraint constraint, ArgumentList argumentList) {
		if (innerModality.isSet() && argumentList.needsQuantification()) {
			// If there are any quantified arguments, set a helper pattern to be lifted so that the appropriate
			// {@code EXISTS} call are added by the {@code DnfLifter}.
			var filteredArgumentList = List.copyOf(argumentList.filteredArguments());
			var quantifiedConstraint = Dnf.builder(constraint.name() + "#quantified")
					.parameters(filteredArgumentList)
					.clause(
							constraint.call(CallPolarity.POSITIVE, argumentList.arguments())
					)
					.build();
			return innerModality.wrapConstraint(quantifiedConstraint)
					.call(CallPolarity.NEGATIVE, filteredArgumentList);
		}

		return innerModality.wrapConstraint(constraint).call(CallPolarity.NEGATIVE, argumentList.arguments());
	}

	private Literal createEquivalenceLiteral(
			ConcreteModality outerModality, boolean positive, ArgumentList argumentList) {
		if (positive) {
			return outerModality.wrapConstraint(ReasoningAdapter.EQUALS_SYMBOL).call(CallPolarity.POSITIVE,
					argumentList.arguments());
		}
		// Interpret {@code x != y} as {@code !equals(x, y)} at all times, even in modal operators.
		return createNegationLiteral(outerModality.negate(), ReasoningAdapter.EQUALS_SYMBOL, argumentList);
	}

	ArgumentList toArgumentList(
			Expr atom, List<Expr> expressions,
			Map<tools.refinery.language.model.problem.Variable, ? extends Variable> localScope,
			List<Literal> literals) {
		var arguments = new ArrayList<NodeVariable>(expressions.size());
		var filteredArguments = LinkedHashSet.<NodeVariable>newLinkedHashSet(expressions.size());
		boolean needsQuantification = false;
		var referenceCounts = ReferenceCounter.computeReferenceCounts(atom);
		for (var expr : expressions) {
			if (!(expr instanceof VariableOrNodeExpr variableOrNodeExpr)) {
				throw new TracedException(expr, "Unsupported argument");
			}
			var variableOrNode = variableOrNodeExpr.getVariableOrNode();
			switch (variableOrNode) {
			case Node node -> {
				int nodeId = getNodeId(node);
				var tempVariable = Variable.of(semanticsUtils.getNameWithoutRootPrefix(node).orElse("_" + nodeId));
				literals.add(new ConstantLiteral(tempVariable, nodeId));
				arguments.add(tempVariable);
				filteredArguments.add(tempVariable);
			}
			case tools.refinery.language.model.problem.Variable problemVariable -> {
				if (isEffectivelySingleton(problemVariable, referenceCounts)) {
					arguments.add(Variable.of(problemVariable.getName()));
					needsQuantification = true;
				} else {
					var variable = localScope.get(problemVariable).asNodeVariable();
					if (variable == null) {
						throw new TracedException(variableOrNode, "Unknown variable: " + problemVariable.getName());
					}
					arguments.add(variable);
					filteredArguments.add(variable);
				}
			}
			default -> throw new TracedException(variableOrNode, "Unknown argument");
			}
		}
		return new ArgumentList(arguments, filteredArguments, needsQuantification);
	}

	private boolean isEffectivelySingleton(tools.refinery.language.model.problem.Variable variable,
										   Map<EObject, Integer> referenceCounts) {
		if (!(variable instanceof ImplicitVariable)) {
			// Parameter variables are never effectively singleton.
			return false;
		}
		if (ProblemUtil.isSingletonVariable(variable)) {
			return true;
		}
		var problem = EcoreUtil2.getContainerOfType(variable, Problem.class);
		if (problem == null) {
			return false;
		}
		int crossReferencesInModel = referenceCounter.countReferences(problem, variable);
		int crossReferencesInAtom = referenceCounts.getOrDefault(variable, 0);
		return crossReferencesInAtom == crossReferencesInModel;
	}

	record ArgumentList(List<NodeVariable> arguments, Set<NodeVariable> filteredArguments,
								boolean needsQuantification) {
	}
}
