/*
 * Copyright (C) 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.escapevelocity;

import com.google.escapevelocity.Parser.Operator;

/**
 * A node in the parse tree representing an expression. Expressions appear inside directives,
 * specifically {@code #set}, {@code #if}, {@code #foreach}, and macro calls. Expressions can
 * also appear inside indices in references, like {@code $x[$i]}.
 *
 * <p>Nontrivial expressions are represented by a tree of {@link ExpressionNode} objects. For
 * example, in {@code #if ($foo.bar < 3)}, the expression {@code $foo.bar < 3} is parsed into
 * a tree that we might describe as<pre>
 * {@link BinaryExpressionNode}(
 *     {@link ReferenceNode.MemberReferenceNode}(
 *         {@link ReferenceNode.PlainReferenceNode}("foo"),
 *         "bar"),
 *     {@link Operator#LESS},
 *     {@link ConstantExpressionNode}(3))
 * </pre>
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class ExpressionNode extends Node {
  ExpressionNode(String resourceName, int lineNumber) {
    super(resourceName, lineNumber);
  }

  @Override
  final void render(EvaluationContext context, StringBuilder output) {
    Object rendered = evaluate(context);
    if (rendered == null) {
      if (isSilent()) { // $!foo for example
        return;
      }
      throw evaluationException("Null value for " + this);
    }
    output.append(rendered);
  }

  /**
   * Returns the source form of this node. This may not be exactly how it appears in the template.
   * For example both {@code $x} and {@code ${x}} end up being the same kind of node, and its
   * {@code toString()} is {@code "$x"}.
   *
   * <p>This method is used in error messages. It is not invoked in normal template evaluation.
   */
  @Override
  public abstract String toString();

  /**
   * Returns the result of evaluating this node in the given context. This result may be used as
   * part of a further operation, for example evaluating {@code 2 + 3} to 5 in order to set
   * {@code $x} to 5 in {@code #set ($x = 2 + 3)}. Or it may be used directly as part of the
   * template output, for example evaluating replacing {@code name} by {@code Fred} in
   * {@code My name is $name.}.
   *
   * <p>This has to be an {@code Object} rather than a {@code String} (or rather than appending to
   * a {@code StringBuilder}) because it can potentially participate in other operations. In the
   * preceding example, the nodes representing {@code 2} and {@code 3} and the node representing
   * {@code 2 + 3} all return {@code Integer}. As another example, in {@code #if ($foo.bar < 3)}
   * the value {@code $foo} is itself an {@link ExpressionNode} which evaluates to the object
   * referenced by the {@code foo} variable, and {@code $foo.bar} is another {@link ExpressionNode}
   * that takes the value from {@code foo} and looks for the property {@code bar} in it.
   */
  abstract Object evaluate(EvaluationContext context);

  /**
   * True if evaluating this expression yields a value that is considered true by Velocity's
   * <a href="http://velocity.apache.org/engine/releases/velocity-1.7/user-guide.html#Conditionals">
   * rules</a>.  A value is false if it is null or equal to Boolean.FALSE.
   * Every other value is true.
   *
   * <p>Note that the text at the similar link
   * <a href="http://velocity.apache.org/engine/devel/user-guide.html#Conditionals">here</a>
   * states that empty collections and empty strings are also considered false, but that is not
   * true.
   */
  boolean isTrue(EvaluationContext context) {
    Object value = evaluate(context);
    if (value instanceof Boolean) {
      return (Boolean) value;
    } else {
      return value != null;
    }
  }

  /**
   * True if this is a defined value and it evaluates to true. This is the same as {@link #isTrue}
   * except that it is allowed for this to be undefined variable, in which it evaluates to false.
   * The method is overridden for plain references so that undefined is the same as false.
   * The reason is to support Velocity's idiom {@code #if ($var)}, where it is not an error
   * if {@code $var} is undefined.
   */
  boolean isDefinedAndTrue(EvaluationContext context) {
    return isTrue(context);
  }

  /**
   * True if a null value for this expression is silently translated to an empty string when
   * substituted into template text. Otherwise it results in an exception.
   */
  boolean isSilent() {
    return false;
  }

  /**
   * The integer result of evaluating this expression, or null if the expression evaluates to null.
   *
   * @throws EvaluationException if evaluating the expression produces an exception, or if it
   *     yields a value that is neither an integer nor null.
   */
  Integer intValue(EvaluationContext context) {
    Object value = evaluate(context);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Integer)) {
      throw evaluationException("Arithmetic is only available on integers, not " + show(value));
    }
    return (Integer) value;
  }

  /**
   * Returns a string representing the given value, for use in error messages. The string
   * includes both the value's {@code toString()} and its type.
   */
  private static String show(Object value) {
    if (value == null) {
      return "null";
    } else {
      return value + " (a " + value.getClass().getName() + ")";
    }
  }

  /**
   * Represents all binary expressions. In {@code #set ($a = $b + $c)}, this will be the type
   * of the node representing {@code $b + $c}.
   */
  static class BinaryExpressionNode extends ExpressionNode {
    final ExpressionNode lhs;
    final Operator op;
    final ExpressionNode rhs;

    BinaryExpressionNode(ExpressionNode lhs, Operator op, ExpressionNode rhs) {
      super(lhs.resourceName, lhs.lineNumber);
      this.lhs = lhs;
      this.op = op;
      this.rhs = rhs;
    }

    @Override public String toString() {
      return operandString(lhs) + " " + op + " " + operandString(rhs);
    }

    // Restore the parentheses in, for example, (2 + 3) * 4.
    private String operandString(ExpressionNode operand) {
      String s = String.valueOf(operand);
      if (operand instanceof BinaryExpressionNode) {
        BinaryExpressionNode binaryOperand = (BinaryExpressionNode) operand;
        if (binaryOperand.op.precedence < op.precedence) {
          return "(" + s + ")";
        }
      }
      return s;
    }

    @Override Object evaluate(EvaluationContext context) {
      switch (op) {
        case OR:
          return lhs.isTrue(context) || rhs.isTrue(context);
        case AND:
          return lhs.isTrue(context) && rhs.isTrue(context);
        case EQUAL:
          return equal(context);
        case NOT_EQUAL:
          return !equal(context);
        default: // fall out
      }
      Integer lhsInt = lhs.intValue(context);
      Integer rhsInt = rhs.intValue(context);
      if (lhsInt == null || rhsInt == null) {
        return nullOperand(lhsInt == null);
      }
      switch (op) {
        case LESS:
          return lhsInt < rhsInt;
        case LESS_OR_EQUAL:
          return lhsInt <= rhsInt;
        case GREATER:
          return lhsInt > rhsInt;
        case GREATER_OR_EQUAL:
          return lhsInt >= rhsInt;
        case PLUS:
          return lhsInt + rhsInt;
        case MINUS:
          return lhsInt - rhsInt;
        case TIMES:
          return lhsInt * rhsInt;
        case DIVIDE:
          return (rhsInt == 0) ? null : lhsInt / rhsInt;
        case REMAINDER:
          return (rhsInt == 0) ? null : lhsInt % rhsInt;
        default:
          throw new AssertionError(op);
      }
    }

    // Mimic Velocity's null-handling.
    private Void nullOperand(boolean leftIsNull) {
      if (op.isInequality()) {
        // If both are null we'll only complain about the left one.
        String operand = leftIsNull ? "Left operand " + lhs : "Right operand " + rhs;
        throw evaluationException(operand + " of " + op + " must not be null");
      }
      return null;
    }

    /**
     * Returns true if {@code lhs} and {@code rhs} are equal according to Velocity.
     *
     * <p>Velocity's <a
     * href="http://velocity.apache.org/engine/releases/velocity-1.7/vtl-reference-guide.html#aifelseifelse_-_Output_conditional_on_truth_of_statements">definition
     * of equality</a> differs depending on whether the objects being compared are of the same
     * class. If so, equality comes from {@code Object.equals} as you would expect.  But if they
     * are not of the same class, they are considered equal if their {@code toString()} values are
     * equal. This means that integer 123 equals long 123L and also string {@code "123"}.  It also
     * means that equality isn't always transitive. For example, two StringBuilder objects each
     * containing {@code "123"} will not compare equal, even though the string {@code "123"}
     * compares equal to each of them.
     */
    private boolean equal(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      Object rhsValue = rhs.evaluate(context);
      if (lhsValue == rhsValue) {
        return true;
      }
      if (lhsValue == null || rhsValue == null) {
        return false;
      }
      if (lhsValue.getClass().equals(rhsValue.getClass())) {
        return lhsValue.equals(rhsValue);
      }
      // Funky equals behaviour specified by Velocity.
      return lhsValue.toString().equals(rhsValue.toString());
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code !$a}.
   */
  static class NotExpressionNode extends ExpressionNode {
    private final ExpressionNode expr;

    NotExpressionNode(ExpressionNode expr) {
      super(expr.resourceName, expr.lineNumber);
      this.expr = expr;
    }

    @Override public String toString() {
      if (expr instanceof BinaryExpressionNode) {
        return "!(" + expr + ")";
      }
      return "!" + expr;
    }

    @Override Object evaluate(EvaluationContext context) {
      return !expr.isTrue(context);
    }
  }
}
