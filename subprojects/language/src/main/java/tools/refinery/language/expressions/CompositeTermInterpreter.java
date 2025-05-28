/*
 * SPDX-FileCopyrightText: 2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.expressions;

import tools.refinery.language.model.problem.BinaryOp;
import tools.refinery.language.model.problem.ComparisonOp;
import tools.refinery.language.model.problem.UnaryOp;
import tools.refinery.language.typesystem.AggregatorName;
import tools.refinery.language.typesystem.DataExprType;
import tools.refinery.logic.AnyAbstractDomain;
import tools.refinery.logic.term.AnyTerm;
import tools.refinery.logic.term.Term;
import tools.refinery.logic.term.truthvalue.TruthValue;

import java.util.List;
import java.util.Optional;

public class CompositeTermInterpreter implements TermInterpreter {
	private final List<TermInterpreter> interpreters;

    public CompositeTermInterpreter(List<TermInterpreter> interpreters) {
        this.interpreters = interpreters;
    }

	@Override
	public Optional<DataExprType> getNegationType(DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.getNegationType(type);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<AnyAbstractDomain> getDomain(DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.getDomain(type);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<AnyTerm> createNegation(DataExprType type, AnyTerm body) {
		for (var interpreter : interpreters) {
			var result = interpreter.createNegation(type, body);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<AnyTerm> createRange(DataExprType type, AnyTerm left, AnyTerm right) {
		for (var interpreter : interpreters) {
			var result = interpreter.createRange(type, left, right);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<AnyTerm> createBinaryOperator(BinaryOp op, DataExprType type, AnyTerm left, AnyTerm right) {
		for (var interpreter : interpreters) {
			var result = interpreter.createBinaryOperator(op, type, left, right);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<DataExprType> getUnaryOperationType(UnaryOp op, DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.getUnaryOperationType(op, type);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public boolean isComparisonSupported(DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.isComparisonSupported(type);
			if (result) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Optional<Term<TruthValue>> createComparison(ComparisonOp op, DataExprType type, AnyTerm left,
													   AnyTerm right) {
		for (var interpreter : interpreters) {
			var result = interpreter.createComparison(op, type, left, right);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public boolean isRangeSupported(DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.isRangeSupported(type);
			if (result) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Optional<DataExprType> getBinaryOperatorType(BinaryOp op, DataExprType leftType, DataExprType rightType) {
		for (var interpreter : interpreters) {
			var result = interpreter.getBinaryOperatorType(op, leftType, rightType);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public boolean isCastSupported(DataExprType fromType, DataExprType toType) {
		for (var interpreter : interpreters) {
			var result = interpreter.isCastSupported(fromType, toType);
			if (result) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Optional<DataExprType> getAggregationType(AggregatorName aggregator, DataExprType type) {
		for (var interpreter : interpreters) {
			var result = interpreter.getAggregationType(aggregator, type);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}
}
