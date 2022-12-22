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

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * A node in the parse tree that is a directive such as {@code #set ($x = $y)}
 * or {@code #if ($x) y #end}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class DirectiveNode extends Node {
  DirectiveNode(String resourceName, int lineNumber) {
    super(resourceName, lineNumber);
  }

  /**
   * A node in the parse tree representing a {@code #set} construct. Evaluating
   * {@code #set ($x = 23)} will set {@code $x} to the value 23. It does not in itself produce
   * any text in the output.
   *
   * <p>Velocity supports setting values within arrays or collections, with for example
   * {@code $set ($x[$i] = $y)}. That is not currently supported here.
   */
  static class SetNode extends DirectiveNode {
    private final String var;
    private final ExpressionNode expression;

    SetNode(String var, ExpressionNode expression) {
      super(expression.resourceName, expression.lineNumber);
      this.var = var;
      this.expression = expression;
    }

    @Override void render(EvaluationContext context, StringBuilder output) {
      context.setVar(var, expression.evaluate(context));
    }
  }

  /**
   * A node in the parse tree representing an {@code #if} construct. All instances of this class
   * have a <i>true</i> subtree and a <i>false</i> subtree. For a plain {@code #if (cond) body
   * #end}, the false subtree will be empty. For {@code #if (cond1) body1 #elseif (cond2) body2
   * #else body3 #end}, the false subtree will contain a nested {@code IfNode}, as if {@code #else
   * #if} had been used instead of {@code #elseif}.
   */
  static class IfNode extends DirectiveNode {
    private final ExpressionNode condition;
    private final Node truePart;
    private final Node falsePart;

    IfNode(
        String resourceName,
        int lineNumber,
        ExpressionNode condition,
        Node trueNode,
        Node falseNode) {
      super(resourceName, lineNumber);
      this.condition = condition;
      this.truePart = trueNode;
      this.falsePart = falseNode;
    }

    @Override void render(EvaluationContext context, StringBuilder output) {
      Node branch = condition.isDefinedAndTrue(context) ? truePart : falsePart;
      branch.render(context, output);
    }
  }

  /**
   * A node in the parse tree representing a {@code #foreach} construct. While evaluating
   * {@code #foreach ($x in $things)}, {$code $x} will be set to each element of {@code $things} in
   * turn. Once the loop completes, {@code $x} will go back to whatever value it had before, which
   * might be undefined. During loop execution, the variable {@code $foreach} is also defined.
   * Velocity defines a number of properties in this variable, but here we only support
   * {@code $foreach.hasNext} and {@code $foreach.index}.
   */
  static class ForEachNode extends DirectiveNode {
    private final String var;
    private final ExpressionNode collection;
    private final Node body;

    ForEachNode(String resourceName, int lineNumber, String var, ExpressionNode in, Node body) {
      super(resourceName, lineNumber);
      this.var = var;
      this.collection = in;
      this.body = body;
    }

    @Override
    void render(EvaluationContext context, StringBuilder output) {
      Object collectionValue = collection.evaluate(context);
      Iterable<?> iterable;
      if (collectionValue instanceof Iterable<?>) {
        iterable = (Iterable<?>) collectionValue;
      } else if (collectionValue instanceof Object[]) {
        iterable = Arrays.asList((Object[]) collectionValue);
      } else if (collectionValue instanceof Map<?, ?>) {
        iterable = ((Map<?, ?>) collectionValue).values();
      } else if (collectionValue == null) {
        return;
      } else {
        throw evaluationException("Not iterable: " + collectionValue);
      }
      Runnable undo = context.setVar(var, null);
      CountingIterator it = new CountingIterator(iterable.iterator());
      Runnable undoForEach = context.setVar("foreach", new ForEachVar(it));
      while (it.hasNext()) {
        context.setVar(var, it.next());
        body.render(context, output);
      }
      undoForEach.run();
      undo.run();
    }

    private static class CountingIterator implements Iterator<Object> {
      private final Iterator<?> iterator;
      private int index = -1;

      CountingIterator(Iterator<?> iterator) {
        this.iterator = iterator;
      }

      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Object next() {
        Object next = iterator.next();
        index++;
        return next;
      }

      int index() {
        return index;
      }
    }

    /**
     *  This class is the type of the variable {@code $foreach} that is defined within
     * {@code #foreach} loops. Its {@link #getHasNext()} method means that we can write
     * {@code #if ($foreach.hasNext)} and likewise for {@link #getIndex()} etc.
     */
    private static class ForEachVar {
      private final CountingIterator iterator;

      ForEachVar(CountingIterator iterator) {
        this.iterator = iterator;
      }

      public boolean getHasNext() {
        return iterator.hasNext();
      }

      public boolean getFirst() {
        return iterator.index() == 0;
      }

      public boolean getLast() {
        return !iterator.hasNext();
      }

      public int getIndex() {
        return iterator.index();
      }

      public int getCount() {
        return iterator.index() + 1;
      }
    }
  }

  /**
   * A node in the parse tree representing a macro call. If the template contains a definition like
   * {@code #macro (mymacro $x $y) ... #end}, then a call of that macro looks like
   * {@code #mymacro (xvalue yvalue)}. The call is represented by an instance of this class. The
   * definition itself does not appear in the parse tree.
   *
   * <p>Evaluating a macro involves temporarily setting the parameter variables ({@code $x $y} in
   * the example) to thunks representing the argument expressions, evaluating the macro body, and
   * restoring any previous values that the parameter variables had.
   */
  static class MacroCallNode extends DirectiveNode {
    private final String name;
    private final ImmutableList<ExpressionNode> thunks;

    MacroCallNode(
        String resourceName,
        int lineNumber,
        String name,
        ImmutableList<ExpressionNode> argumentNodes) {
      super(resourceName, lineNumber);
      this.name = name;
      this.thunks = argumentNodes;
    }

    @Override
    void render(EvaluationContext context, StringBuilder output) {
      Macro macro = context.getMacro(name);
      if (macro == null) {
        throw evaluationException(
            "#" + name + " is neither a standard directive nor a macro that has been defined");
      }
      if (thunks.size() != macro.parameterCount()) {
        throw evaluationException(
            "Wrong number of arguments to #"
                + name
                + ": expected "
                + macro.parameterCount()
                + ", got "
                + thunks.size());
      }
      macro.render(context, thunks, output);
    }
  }
}
