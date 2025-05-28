/*
 * SPDX-FileCopyrightText: 2023-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.semantics.metadata;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.naming.IQualifiedNameProvider;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.jetbrains.annotations.Nullable;
import tools.refinery.language.documentation.DocumentationCommentParser;
import tools.refinery.language.documentation.TypeHashProvider;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.language.semantics.TracedException;
import tools.refinery.language.utils.ProblemUtil;
import tools.refinery.store.model.Model;
import tools.refinery.store.reasoning.ReasoningAdapter;
import tools.refinery.store.reasoning.literal.Concreteness;
import tools.refinery.store.reasoning.representation.AnyPartialSymbol;
import tools.refinery.store.reasoning.representation.PartialRelation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MetadataCreator {
	private static final List<String> CLASS_PARAMETER_NAMES = List.of(DocumentationCommentParser.CLASS_PARAMETER_NAME);
	private static final List<String> ENUM_PARAMETER_NAMES = List.of(DocumentationCommentParser.ENUM_PARAMETER_NAME);
	private static final List<String> REFERENCE_PARAMETER_NAMES = List.of(
			DocumentationCommentParser.REFERENCE_SOURCE_PARAMETER_NAME,
			DocumentationCommentParser.REFERENCE_TARGET_PARAMETER_NAME);

	@Inject
	private IScopeProvider scopeProvider;

	@Inject
	private IQualifiedNameProvider qualifiedNameProvider;

	@Inject
	private IQualifiedNameConverter qualifiedNameConverter;

	@Inject
	private TypeHashProvider typeHashProvider;

	@Inject
	private Provider<NodeMetadataFactory> nodeMetadataFactoryProvider;

	private ProblemTrace problemTrace;
	private boolean preserveNewNodes;
	private IScope nodeScope;
	private IScope relationScope;

	public void setProblemTrace(ProblemTrace problemTrace) {
		if (this.problemTrace != null) {
			throw new IllegalArgumentException("Problem trace was already set");
		}
		this.problemTrace = problemTrace;
		var problem = problemTrace.getProblem();
		nodeScope = scopeProvider.getScope(problem, ProblemPackage.Literals.NODE_ASSERTION_ARGUMENT__NODE);
		relationScope = scopeProvider.getScope(problem, ProblemPackage.Literals.ABSTRACT_ASSERTION__RELATION);
	}

	public void setPreserveNewNodes(boolean preserveNewNodes) {
		this.preserveNewNodes = preserveNewNodes;
	}

	public NodesMetadata getNodesMetadata(Model model, Concreteness concreteness) {
		int nodeCount = model.getAdapter(ReasoningAdapter.class).getNodeCount();
		var nodeTrace = problemTrace.getNodeTrace();
		var nodes = new NodeMetadata[Math.max(nodeTrace.size(), nodeCount)];
		var nodeMetadataFactory = nodeMetadataFactoryProvider.get();
		nodeMetadataFactory.initialize(problemTrace, concreteness, model);
		for (var entry : nodeTrace.keyValuesView()) {
			var node = entry.getOne();
			var id = entry.getTwo();
			nodes[id] = getNodeMetadata(id, node, nodeMetadataFactory);
		}
		for (int i = 0; i < nodes.length; i++) {
			if (nodes[i] == null) {
				nodes[i] = nodeMetadataFactory.createFreshlyNamedMetadata(i);
			}
		}
		return new NodesMetadata(List.of(nodes));
	}

	private NodeMetadata getNodeMetadata(int nodeId, Node node, NodeMetadataFactory nodeMetadataFactory) {
		var kind = getNodeKind(node);
		if (!preserveNewNodes && kind == NodeKind.MULTI && nodeMetadataFactory.nodeExists(nodeId)) {
			return nodeMetadataFactory.createFreshlyNamedMetadata(nodeId);
		}
		var qualifiedName = getQualifiedName(node);
		var simpleName = getSimpleName(node, qualifiedName, nodeScope);
		return nodeMetadataFactory.doCreateMetadata(nodeId, qualifiedNameConverter.toString(qualifiedName),
				qualifiedNameConverter.toString(simpleName), kind);
	}

	private NodeKind getNodeKind(Node node) {
		if (ProblemUtil.isAtomNode(node)) {
			return NodeKind.ATOM;
		} else if (ProblemUtil.isMultiNode(node)) {
			return NodeKind.MULTI;
		} else {
			return NodeKind.DEFAULT;
		}
	}

	public List<RelationMetadata> getRelationsMetadata() {
		var relationTrace = problemTrace.getRelationTrace();
		var relations = new ArrayList<RelationMetadata>(relationTrace.size());
		for (var entry : relationTrace.entrySet()) {
			var relation = entry.getKey();
			var partialRelation = entry.getValue();
			System.out.println(relation);
			if (relation instanceof ReferenceDeclaration referenceDeclaration &&
					referenceDeclaration.getReferenceType() instanceof DatatypeDeclaration) {
				// it is an attribute (most likely)

			}
			else{
				var metadata = getRelationMetadata(relation, partialRelation.asPartialRelation());
				relations.add(metadata);
			}
		}
		return Collections.unmodifiableList(relations);
	}

	private RelationMetadata getRelationMetadata(Relation relation, AnyPartialSymbol partialSymbol) {
		var qualifiedName = getQualifiedName(relation);
		var qualifiedNameString = qualifiedNameConverter.toString(qualifiedName);
		var simpleName = getSimpleName(relation, qualifiedName, relationScope);
		var simpleNameString = qualifiedNameConverter.toString(simpleName);
		var arity = partialSymbol.arity();
		var parameterNames = getParameterNames(relation);
		var detail = getRelationDetail(relation, partialSymbol);
		return new RelationMetadata(qualifiedNameString, simpleNameString, arity, parameterNames, detail);
	}

	@Nullable
	private List<String> getParameterNames(Relation relation) {
		return switch (relation) {
			case ClassDeclaration ignored -> CLASS_PARAMETER_NAMES;
			case EnumDeclaration ignored -> ENUM_PARAMETER_NAMES;
			case ReferenceDeclaration ignored -> REFERENCE_PARAMETER_NAMES;
			case PredicateDefinition predicateDefinition -> getPredicateParameterNames(predicateDefinition);
			default -> null;
		};
	}

	private List<String> getPredicateParameterNames(PredicateDefinition predicateDefinition) {
		return predicateDefinition.getParameters().stream()
				.map(parameter -> {
					var qualifiedParameterName = QualifiedName.create(parameter.getName());
					return qualifiedNameConverter.toString(qualifiedParameterName);
				})
				.toList();
	}

	private RelationDetail getRelationDetail(Relation relation, AnyPartialSymbol partialSymbol) {
		return switch (relation) {
			case ClassDeclaration classDeclaration -> getClassDetail(classDeclaration);
			case ReferenceDeclaration ignored -> getReferenceDetail(partialSymbol);
			case EnumDeclaration enumDeclaration -> getEnumDetail(enumDeclaration);
			case PredicateDefinition predicateDefinition -> getPredicateDetail(predicateDefinition);
			default -> throw new TracedException(relation, "Unknown relation");
		};
	}

	private RelationDetail getClassDetail(ClassDeclaration classDeclaration) {
		var typeHash = typeHashProvider.getTypeHash(classDeclaration);
		return new RelationDetail.Class(classDeclaration.isAbstract(), typeHash);
	}

	private RelationDetail getReferenceDetail(AnyPartialSymbol partialSymbol) {
		var metamodel = problemTrace.getMetamodel();
		if (partialSymbol instanceof PartialRelation) {
			var opposite = metamodel.oppositeReferences().get(partialSymbol);
			if (opposite == null) {
				boolean isContainment = metamodel.containmentHierarchy().containsKey(partialSymbol);
				return new RelationDetail.Reference(isContainment);
			} else {
				boolean isContainer = metamodel.containmentHierarchy().containsKey(opposite);
				return new RelationDetail.Opposite(opposite.name(), isContainer);
			}
		}
		else
		{
			// hmm there is no attribute yet, could implement one...
			var typeHash = typeHashProvider.getTypeHash(problemTrace.getRelation(partialSymbol));
			return new RelationDetail.Class(false,typeHash);
		}
	}

	private RelationDetail getEnumDetail(EnumDeclaration enumDeclaration) {
		var typeHash = typeHashProvider.getTypeHash(enumDeclaration);
		return new RelationDetail.Class(false, typeHash);
	}

	private RelationDetail getPredicateDetail(PredicateDefinition predicate) {
		if (ProblemUtil.isComputedValuePredicate(predicate) &&
				predicate.eContainer() instanceof PredicateDefinition parentDefinition) {
			var parentQualifiedName = getQualifiedName(parentDefinition);
			var computedOf = qualifiedNameConverter.toString(parentQualifiedName);
			return new RelationDetail.Computed(computedOf);
		}
		PredicateDetailKind kind = PredicateDetailKind.DEFINED;
		if (ProblemUtil.isBasePredicate(predicate)) {
			kind = PredicateDetailKind.BASE;
		} else if (ProblemUtil.isError(predicate)) {
			kind = PredicateDetailKind.ERROR;
		} else if (ProblemUtil.isShadow(predicate)) {
			kind = PredicateDetailKind.SHADOW;
		}
		return new RelationDetail.Predicate(kind);
	}

	private QualifiedName getQualifiedName(EObject eObject) {
		var qualifiedName = qualifiedNameProvider.getFullyQualifiedName(eObject);
		if (qualifiedName == null) {
			throw new TracedException(eObject, "Unknown qualified name");
		}
		return qualifiedName;
	}

	private QualifiedName getSimpleName(EObject eObject, QualifiedName qualifiedName, IScope scope) {
		var descriptions = scope.getElements(eObject);
		var names = new ArrayList<QualifiedName>();
		for (var description : descriptions) {
			// {@code getQualifiedName()} will refer to the full name for objects that are loaded from the global
			// scope, but {@code getName()} returns the qualified name that we set in
			// {@code ProblemResourceDescriptionStrategy}.
			names.add(description.getName());
		}
		names.sort(Comparator.comparingInt(QualifiedName::getSegmentCount));
		for (var simpleName : names) {
			if (names.contains(simpleName) && isUnique(scope, simpleName)) {
				return simpleName;
			}
		}
		throw new TracedException(eObject, "Ambiguous qualified name: " +
				qualifiedNameConverter.toString(qualifiedName));
	}

	private boolean isUnique(IScope scope, QualifiedName name) {
		var iterator = scope.getElements(name).iterator();
		if (!iterator.hasNext()) {
			return false;
		}
		iterator.next();
		return !iterator.hasNext();
	}
}
