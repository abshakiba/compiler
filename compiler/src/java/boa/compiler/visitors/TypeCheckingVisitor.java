package boa.compiler.visitors;

import java.io.IOException;
import java.util.*;

import boa.aggregators.AggregatorSpec;
import boa.compiler.SymbolTable;
import boa.compiler.TypeException;
import boa.compiler.ast.*;
import boa.compiler.ast.expressions.*;
import boa.compiler.ast.literals.*;
import boa.compiler.ast.statements.*;
import boa.compiler.ast.types.*;
import boa.types.*;

/**
 * Prescan the program and check that all variables are consistently typed.
 * 
 * @author anthonyu
 * @author rdyer
 */
public class TypeCheckingVisitor extends AbstractVisitor<SymbolTable> {
	/** {@inheritDoc} */
	@Override
	public void visit(final Program n, final SymbolTable env) {
		n.env = env;

		for (final Statement s : n.getStatements())
			s.accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Call n, final SymbolTable env) {
		n.env = env;

		if (n.getArgsSize() > 0)
			n.type = new BoaTuple(this.check(n.getArgs(), env));
		else
			n.type = new BoaArray();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Comparison n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		n.type = n.getLhs().type;

		if (n.hasRhs()) {
			n.getRhs().accept(this, env);

			if (!n.getRhs().type.compares(n.getLhs().type))
				throw new TypeException(n.getRhs(), "incompatible types for comparison: required '" + n.getLhs().type + "', found '" + n.getRhs().type + "'");

			if (n.getLhs().type instanceof BoaString || n.getLhs().type instanceof BoaProtoTuple)
				if (!n.getOp().equals("==") && !n.getOp().equals("!="))
					throw new TypeException(n.getLhs(), "invalid comparison operator '" + n.getOp() + "' for type '" + n.getLhs().type + "'");

			n.type = new BoaBool();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Component n, final SymbolTable env) {
		n.env = env;

		n.getType().accept(this, env);

		if (n.hasIdentifier()) {
			n.type = new BoaName(n.getType().type, n.getIdentifier().getToken());
			env.set(n.getIdentifier().getToken(), n.getType().type);
			n.getIdentifier().accept(this, env);
		} else {
			n.type = n.getType().type;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Composite n, final SymbolTable env) {
		n.env = env;

		if (n.isEmpty()) {
			n.type = new BoaMap();
			return;
		}
		// FIXME composite could be truly empty, not 'map empty'
//		n.type = new BoaArray();

		if (n.getPairsSize() > 0)
			n.type = checkPairs(n.getPairs(), env);
		else
			n.type = check(n.getExprs(), env).get(0);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Conjunction n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		final BoaType ltype = n.getLhs().type;
		n.type = ltype;

		if (n.getRhsSize() > 0) {
			if (!(ltype instanceof BoaBool))
				throw new TypeException(n.getLhs(), "incompatible types for conjunction: required 'bool', found '" + ltype + "'");

			for (final Comparison c : n.getRhs()) {
				c.accept(this, env);
				if (!(c.type instanceof BoaBool))
					throw new TypeException(c, "incompatible types for conjunction: required 'bool', found '" + c.type + "'");
			}

			n.type = new BoaBool();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Factor n, final SymbolTable env) {
		n.env = env;

		BoaType type = null;

		if (n.getOpsSize() > 0)
			for (final Node node : n.getOps()) {
				if (node instanceof Selector) {
					if (type == null) {
						n.getOperand().accept(this, env);
						type = n.getOperand().type;
					}

					if (type instanceof BoaName)
						type = ((BoaName) type).getType();

					env.setOperandType(type);
					node.accept(this, env);
					type = node.type;
				} else if (node instanceof Index) {
					if (type == null) {
						n.getOperand().accept(this, env);
						type = n.getOperand().type;
					}

					node.accept(this, env);
					final BoaType index = node.type;

					if (type instanceof BoaArray) {
						if (!(index instanceof BoaInt))
							throw new TypeException(node, "invalid operand type '" + index + "' for indexing into array");

						type = ((BoaArray) type).getType();
					} else if (type instanceof BoaProtoList) {
						if (!(index instanceof BoaInt))
							throw new TypeException(node, "invalid operand type '" + index + "' for indexing into array");

						type = ((BoaProtoList) type).getType();
					} else if (type instanceof BoaMap) {
						if (!((BoaMap) type).getIndexType().assigns(index))
							throw new TypeException(node, "invalid operand type '" + index + "' for indexing into '" + type + "'");

						type = ((BoaMap) type).getType();
					} else {
						throw new TypeException(node, "invalid operand type '" + type + "' for indexing expression");
					}
				} else {
					node.accept(this, env);
					n.getOperand().env = env;

					final List<BoaType> formalParameters = this.check((Call) node, env);

					final FunctionFindingVisitor v = new FunctionFindingVisitor(formalParameters);
					v.start((Identifier)n.getOperand(), env);
					node.type = v.getFunction().erase(formalParameters);
					n.type = node.type;
					return;
				}
				node.type = type;
			}
		else {
			n.getOperand().accept(this, env);
			type = n.getOperand().type;
		}

		n.type = type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Identifier n, final SymbolTable env) {
		n.env = env;

		if (env.hasType(n.getToken()))
			n.type = SymbolTable.getType(n.getToken());
		else
			try {
				n.type = env.get(n.getToken());
			} catch (final RuntimeException e) {
				throw new TypeException(n, "invalid identifier '" + n.getToken() + "'", e);
			}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Index n, final SymbolTable env) {
		n.env = env;

		n.getStart().accept(this, env);
		n.type = n.getStart().type;

		if (n.getStart().type == null)
			throw new RuntimeException();

		if (n.hasEnd()) {
			if (!(n.getStart().type instanceof BoaInt))
				throw new TypeException(n.getStart(), "invalid type '" + n.getStart().type + "' for slice expression");

			n.getEnd().accept(this, env);
			if (!(n.getEnd().type instanceof BoaInt))
				throw new TypeException(n.getEnd(), "invalid type '" + n.getEnd().type + "' for slice expression");
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Pair n, final SymbolTable env) {
		n.env = env;
		n.getExpr1().accept(this, env);
		n.getExpr2().accept(this, env);
		n.type = new BoaMap(n.getExpr1().type, n.getExpr2().type);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Selector n, final SymbolTable env) {
		n.env = env;

		final String selector = n.getId().getToken();
		BoaType type = env.getOperandType();

		if (type instanceof BoaProtoMap) {
			if (!((BoaProtoMap) type).hasAttribute(selector))
				throw new TypeException(n.getId(), type + " has no member named '" + selector + "'");
		} else if (type instanceof BoaTuple) {
			if (!((BoaTuple) type).hasMember(selector))
				throw new TypeException(n.getId(), "'" + type + "' has no member named '" + selector + "'");

			type = ((BoaTuple) type).getMember(selector);
		} else {
			throw new TypeException(n, "invalid operand type '" + type + "' for member selection");
		}

		n.type = type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Term n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		final BoaType accepts = n.getLhs().type;
		n.type = accepts;

		if (n.getRhsSize() > 0) {
			BoaScalar type;

			if (accepts instanceof BoaFunction)
				type = (BoaScalar) ((BoaFunction) accepts).getType();
			else
				type = (BoaScalar) accepts;

			for (final Factor f : n.getRhs()) {
				f.accept(this, env);
				type = type.arithmetics(f.type);
			}

			n.type = type;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final UnaryFactor n, final SymbolTable env) {
		n.env = env;
		n.getFactor().accept(this, env);
		n.type = n.getFactor().type;
	}

	//
	// statements
	//
	/** {@inheritDoc} */
	@Override
	public void visit(final AssignmentStatement n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		n.getRhs().accept(this, env);

		if (!(n.getLhs().type instanceof BoaArray && n.getRhs().type instanceof BoaTuple))
			if (!n.getLhs().type.assigns(n.getRhs().type))
				throw new TypeException(n.getRhs(), "incompatible types for assignment: required '" + n.getLhs().type + "', found '" + n.getRhs().type + "'");

		n.type = n.getLhs().type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final Block n, final SymbolTable env) {
		SymbolTable st;

		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		for (final Node s : n.getStatements())
			s.accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final BreakStatement n, final SymbolTable env) {
		n.env = env;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ContinueStatement n, final SymbolTable env) {
		n.env = env;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final DoStatement n, final SymbolTable env) {
		SymbolTable st;

		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getCondition().accept(this, st);
		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final EmitStatement n, final SymbolTable env) {
		n.env = env;

		n.getId().accept(this, env);
		final String id = n.getId().getToken();
		final BoaType type = n.getId().type;

		if (type == null)
			throw new TypeException(n.getId(), "emitting to undeclared output variable '" + id + "'");
		if (!(type instanceof BoaTable))
			throw new TypeException(n.getId(), "emitting to non-output variable '" + id + "'");

		final BoaTable t = (BoaTable) type;

		if (n.getIndicesSize() != t.countIndices())
			throw new TypeException(n.getId(), "output variable '" + id + "': incorrect number of indices for '" + id + "': required " + t.countIndices() + ", found " + n.getIndicesSize());

		if (n.getIndicesSize() > 0)
			for (int i = 0; i < n.getIndicesSize() && i < t.countIndices(); i++) {
				n.getIndice(i).accept(this, env);
				if (!t.getIndex(i).assigns(n.getIndice(i).type))
					throw new TypeException(n.getIndice(i), "output variable '" + id + "': incompatible types for index '" + i + "': required '" + t.getIndex(i) + "', found '" + n.getIndice(i) + "'");
			}

		n.getValue().accept(this, env);
		if (!t.accepts(n.getValue().type))
			throw new TypeException(n.getValue(), "output variable '" + id + "': incompatible emit value types: required '" + t.getType() + "', found '" + n.getValue().type + "'");

		if (n.hasWeight()) {
			if (t.getWeightType() == null)
				throw new TypeException(n.getWeight(), "output variable '" + id + "': emit contains a weight, but variable not declared with a weight");

			n.getWeight().accept(this, env);

			if (!t.acceptsWeight(n.getWeight().type))
				throw new TypeException(n.getWeight(), "output variable '" + id + "': incompatible types for weight: required '" + t.getWeightType() + "', found '" + n.getWeight().type + "'");
		} else if (t.getWeightType() != null)
			throw new TypeException(n, "output variable '" + id + "': emit must specify a weight");
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ExistsStatement n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getVar().accept(this, st);

		n.getCondition().accept(this, st);
		if (!(n.getCondition().type instanceof BoaBool))
			throw new TypeException(n.getCondition(), "incompatible types for exists condition: required 'boolean', found '" + n.getCondition().type + "'");

		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ExprStatement n, final SymbolTable env) {
		n.env = env;

		n.getExpr().accept(this, env);
		n.type = n.getExpr().type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ForeachStatement n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getVar().accept(this, st);

		n.getCondition().accept(this, st);
		if (!(n.getCondition().type instanceof BoaBool))
			throw new TypeException(n.getCondition(), "incompatible types for foreach condition: required 'boolean', found '" + n.getCondition().type + "'");

		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ForStatement n, final SymbolTable env) {
		SymbolTable st;

		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		if (n.hasInit())
			n.getInit().accept(this, st);

		if (n.hasCondition())
			n.getCondition().accept(this, st);

		if (n.hasUpdate())
			n.getUpdate().accept(this, st);

		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final IfAllStatement n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getVar().accept(this, st);

		n.getCondition().accept(this, st);
		if (!(n.getCondition().type instanceof BoaBool))
			throw new TypeException(n.getCondition(), "incompatible types for ifall condition: required 'boolean', found '" + n.getCondition().type + "'");

		n.getBody().accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final IfStatement n, final SymbolTable env) {
		n.env = env;

		n.getCondition().accept(this, env);

		if (!(n.getCondition().type instanceof BoaBool))
			if (!(n.getCondition().type instanceof BoaFunction && ((BoaFunction) n.getCondition().type).getType() instanceof BoaBool))
				throw new TypeException(n.getCondition(), "incompatible types for if condition: required 'boolean', found '" + n.getCondition().type + "'");

		n.getBody().accept(this, env);

		if (n.hasElse())
			n.getElse().accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final PostfixStatement n, final SymbolTable env) {
		n.env = env;

		n.getExpr().accept(this, env);
		if (!(n.getExpr().type instanceof BoaInt))
			throw new TypeException(n.getExpr(), "incompatible types for operator '" + n.getOp() + "': required 'int', found '" + n.getExpr().type + "'");

		n.type = n.getExpr().type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ResultStatement n, final SymbolTable env) {
		throw new RuntimeException("unimplemented");
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ReturnStatement n, final SymbolTable env) {
		if (env.getIsBeforeVisitor())
			throw new TypeException(n, "return statement not allowed inside visitors");

		n.env = env;

		// FIXME rdyer need to check return type matches function declaration's return
		if (n.hasExpr()) {
			n.getExpr().accept(this, env);
			n.type = n.getExpr().type;
		} else {
			n.type = new BoaAny();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final StopStatement n, final SymbolTable env) {
		n.env = env;

		if (!env.getIsBeforeVisitor())
			throw new TypeException(n, "Stop statement only allowed inside 'before' visits");
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final SwitchCase n, final SymbolTable env) {
		n.env = env;

		for (final Expression e : n.getCases())
			e.accept(this, env);

		for (final Statement s : n.getStmts())
			s.accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final SwitchStatement n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getCondition().accept(this, st);
		final BoaType expr = n.getCondition().type;
		if (!(expr instanceof BoaInt) && !(expr instanceof BoaProtoMap))
			throw new TypeException(n.getCondition(), "incompatible types for switch expression: required 'int' or 'enum', found: " + expr);

		for (final SwitchCase sc : n.getCases()) {
			sc.accept(this, st);
			for (final Expression e : sc.getCases())
				if (!expr.assigns(e.type))
					throw new TypeException(e, "incompatible types for case expression: required '" + expr + "', found '" + e.type + "'");
		}

		n.getDefault().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final VarDeclStatement n, final SymbolTable env) {
		n.env = env;

		final String id = n.getId().getToken();

		if (env.hasGlobal(id))
			throw new TypeException(n.getId(), "name conflict: constant '" + id + "' already exists");
		if (env.hasLocal(id))
			throw new TypeException(n.getId(), "variable '" + id + "' already declared as '" + env.get(id) + "'");

		BoaType rhs = null;
		if (n.hasInitializer()) {
			n.getInitializer().accept(this, env);
			rhs = n.getInitializer().type;

			// if type is a function but rhs isnt a function decl,
			// then its a call so the lhs type is the return type
			if (rhs instanceof BoaFunction) {
				final IsFunctionVisitor v = new IsFunctionVisitor();
				v.start(n.getInitializer());
				if (!v.isFunction())
					rhs = ((BoaFunction)rhs).getType();
			}
		}

		BoaType lhs;
		if (n.hasType()) {
			n.getType().accept(this, env);
			lhs = n.getType().type;

			if (lhs instanceof BoaArray && rhs instanceof BoaTuple)
				rhs = new BoaArray(((BoaTuple)rhs).getMember(0));

			if (rhs != null && !lhs.assigns(rhs) && !env.hasCast(rhs, lhs))
				throw new TypeException(n.getInitializer(), "incorrect type '" + rhs + "' for assignment to '" + id + ": " + lhs + "'");
		} else {
			if (rhs == null)
				throw new TypeException(n, "variable declaration requires an explicit type or an initializer");

			lhs = rhs;
		}

		if (lhs instanceof BoaFunction && (env.hasFunction(id) || env.hasLocalFunction(id)))
			throw new TypeException(n.getId(), "name conflict: a function '" + id + "' already exists");

		env.set(id, lhs);
		n.type = lhs;
		n.getId().accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final VisitStatement n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		st.setIsBeforeVisitor(n.isBefore());
		n.env = st;

		if (n.hasComponent()) {
			final Component c = n.getComponent();

			// first see if the type is valid
			c.getType().accept(this, st);
			if (c.getType().type == null)
				throw new TypeException(c.getType(), "Invalid type '" + ((Identifier)c.getType()).getToken() + "'");

			// then update the table
			st.set(c.getIdentifier().getToken(), c.getType().type);
			c.getIdentifier().accept(this, st);
		} else if (!n.hasWildcard()) {
			for (final Identifier id : n.getIdList()) {
				if (SymbolTable.getType(id.getToken()) == null)
					throw new TypeException(id, "Invalid type '" + id.getToken() + "'");
				id.accept(this, st);
			}
		}

		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final WhileStatement n, final SymbolTable env) {
		SymbolTable st;

		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getCondition().accept(this, st);
		n.getBody().accept(this, st);
	}

	//
	// expressions
	//
	/** {@inheritDoc} */
	@Override
	public void visit(final Expression n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		final BoaType ltype = n.getLhs().type;
		n.type = ltype;

		if (n.getRhsSize() > 0) {
			if (!(ltype instanceof BoaBool))
				throw new TypeException(n.getLhs(), "incompatible types for disjunction: required 'bool', found '" + ltype + "'");

			for (final Conjunction c : n.getRhs()) {
				c.accept(this, env);
				if (!(c.type instanceof BoaBool))
					throw new TypeException(c, "incompatible types for disjunction: required 'bool', found '" + c.type + "'");
			}

			n.type = new BoaBool();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final FunctionExpression n, final SymbolTable env) {
		SymbolTable st;
		try {
			st = env.cloneNonLocals();
		} catch (final IOException e) {
			throw new RuntimeException(e.getClass().getSimpleName() + " caught", e);
		}

		n.env = st;

		n.getType().accept(this, st);
		n.type = n.getType().type;
		n.getBody().accept(this, st);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final ParenExpression n, final SymbolTable env) {
		n.env = env;
		n.getExpression().accept(this, env);
		n.type = n.getExpression().type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final SimpleExpr n, final SymbolTable env) {
		n.env = env;

		n.getLhs().accept(this, env);
		BoaType type = n.getLhs().type;

		for (final Term t : n.getRhs()) {
			t.accept(this, env);
			type = type.arithmetics(t.type);
		}

		n.type = type;
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final StatementExpr n, final SymbolTable env) {
		throw new RuntimeException("unimplemented");
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final VisitorExpression n, final SymbolTable env) {
		n.env = env;
		n.getType().accept(this, env);
		n.getBody().accept(this, env);
		n.type = n.getType().type;
	}

	//
	// literals
	//
	/** {@inheritDoc} */
	@Override
	public void visit(final BytesLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaBytes();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final CharLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaInt();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final FloatLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaFloat();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final IntegerLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaInt();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final StringLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaString();
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final TimeLiteral n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaTime();
	}

	//
	// types
	//
	/** {@inheritDoc} */
	@Override
	public void visit(final ArrayType n, final SymbolTable env) {
		n.env = env;
		n.getValue().accept(this, env);
		n.type = new BoaArray(n.getValue().type);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final FunctionType n, final SymbolTable env) {
		n.env = env;

		final BoaType[] params = new BoaType[n.getArgsSize()];
		if (n.getArgsSize() > 0) {
			int i = 0;
			for (final Component c : n.getArgs()) {
				c.getType().accept(this, env);
				params[i++] = new BoaName(c.getType().type, c.getIdentifier().getToken());
				env.set(c.getIdentifier().getToken(), c.getType().type);
				c.getIdentifier().accept(this, env);
			}
		}

		BoaType ret = new BoaAny();
		if (n.hasType()) {
			n.getType().accept(this, env);
			ret = n.getType().type;
		}

		n.type = new BoaFunction(ret, params);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final MapType n, final SymbolTable env) {
		n.env = env;
		n.getValue().accept(this, env);
		n.getIndex().accept(this, env);
		n.type = new BoaMap(n.getValue().type, n.getIndex().type);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final OutputType n, final SymbolTable env) {
		n.env = env;

		List<BoaScalar> indexTypes = null;
		if (n.getIndicesSize() > 0) {
			indexTypes = new ArrayList<BoaScalar>();

			for (final Component c : n.getIndices()) {
				c.accept(this, env);

				if (!(c.type instanceof BoaScalar))
					throw new TypeException(c, "incorrect type '" + c.type + "' for index");

				indexTypes.add((BoaScalar) c.type);
			}
		}

		n.getType().accept(this, env);
		final BoaType type = n.getType().type;

		final AggregatorSpec annotation;
		try {
			annotation = env.getAggregators(n.getId().getToken(), type).get(0).getAnnotation(AggregatorSpec.class);
		} catch (final RuntimeException e) {
			throw new TypeException(n, e.getMessage(), e);
		}

		BoaScalar tweight = null;
		if (n.hasWeight()) {
			if (annotation.weightType().equals("none"))
				throw new TypeException(n.getWeight(), "unexpected weight for table declaration");

			final BoaType aweight = SymbolTable.getType(annotation.weightType());
			n.getWeight().accept(this, env);
			tweight = (BoaScalar) n.getWeight().type;

			if (!aweight.assigns(tweight))
				throw new TypeException(n.getWeight(), "incorrect weight type for table declaration");
		} else if (!annotation.weightType().equals("none"))
			throw new TypeException(n, "missing weight for table declaration");

		if (n.getArgsSize() > 0 && annotation.formalParameters().length == 0)
			throw new TypeException(n.getArgs(), "table '" + n.getId().getToken() + "' takes no arguments");

		n.type = new BoaTable(type, indexTypes, tweight);
		env.set(n.getId().getToken(), n.type);
		n.getId().accept(this, env);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final StackType n, final SymbolTable env) {
		n.env = env;
		n.getValue().accept(this, env);
		n.type = new BoaStack(n.getValue().type);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final TupleType n, final SymbolTable env) {
		n.env = env;

		final List<BoaType> types = new ArrayList<BoaType>();

		for (final Component c : n.getMembers()) {
			c.accept(this, env);
			types.add(c.type);
		}

		n.type = new BoaTuple(types);
	}

	/** {@inheritDoc} */
	@Override
	public void visit(final VisitorType n, final SymbolTable env) {
		n.env = env;
		n.type = new BoaVisitor();
	}

	private List<BoaType> check(final Call c, final SymbolTable env) {
		if (c.getArgsSize() > 0)
			return this.check(c.getArgs(), env);

		return new ArrayList<BoaType>();
	}

	private List<BoaType> check(final List<Expression> el, final SymbolTable env) {
		final List<BoaType> types = new ArrayList<BoaType>();

		for (final Expression e : el) {
			e.accept(this, env);
			types.add(assignableType(e.type));
		}

		return types;
	}

	private BoaType checkPairs(final List<Pair> pl, final SymbolTable env) {
		pl.get(0).accept(this, env);
		final BoaMap boaMap = (BoaMap) pl.get(0).type;

		for (final Pair p : pl) {
			p.accept(this, env);
			if (!boaMap.assigns(p.type))
				throw new TypeException(p, "incompatible types: required '" + boaMap + "', found '" + p.type + "'");
		}

		return boaMap;
	}

	private BoaType assignableType(BoaType t) {
		if (t instanceof BoaFunction)
			return ((BoaFunction) t).getType();
		return t;
	}
}
