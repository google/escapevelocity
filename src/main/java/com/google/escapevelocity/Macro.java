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

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A macro definition. Macros appear in templates using the syntax {@code #macro (m $x $y) ... #end}
 * and each one produces an instance of this class. Evaluating a macro involves setting the
 * parameters (here {$x $y)} and evaluating the macro body. Macro arguments are call-by-name, which
 * means that we need to set each parameter variable to the node in the parse tree that corresponds
 * to it, and arrange for that node to be evaluated when the variable is actually referenced.
 *
 * <p>There are two ways to invoke a macro. {@code #m('foo', 'bar')} sets $x and $y. {@code
 * #@m('foo', 'bar') ... #end} sets $x and $y, and also sets $bodyContent to the template text
 * {@code ...}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Macro {
  private final int definitionLineNumber;
  private final String name;
  private final ImmutableList<String> parameterNames;
  private final Node macroBody;

  Macro(int definitionLineNumber, String name, List<String> parameterNames, Node macroBody) {
    this.definitionLineNumber = definitionLineNumber;
    this.name = name;
    this.parameterNames = ImmutableList.copyOf(parameterNames);
    this.macroBody = macroBody;
  }

  int parameterCount() {
    return parameterNames.size();
  }

  /**
   * Renders a call to this macro with the arguments in {@code thunks} and with a possibly-null
   * {@code bodyContent}. The {@code bodyContent} is non-null if the macro call looks like
   * {@code #@foo(...) ... #end}; the {@code #@} indicates that the text up to the matching
   * {@code #end} should be made available as the variable {@code $bodyContent} inside the macro.
   */
  void render(
      EvaluationContext context,
      List<ExpressionNode> thunks,
      Node bodyContent,
      StringBuilder output) {
    try {
      Verify.verify(thunks.size() == parameterNames.size(), "Argument mismatch for %s", name);
      Map<String, ExpressionNode> parameterThunks = new LinkedHashMap<>();
      for (int i = 0; i < parameterNames.size(); i++) {
        parameterThunks.put(parameterNames.get(i), thunks.get(i));
      }
      EvaluationContext newContext =
          new MacroEvaluationContext(parameterThunks, context, bodyContent);
      macroBody.render(newContext, output);
    } catch (EvaluationException e) {
      EvaluationException newException = new EvaluationException(
          "In macro #" + name + " defined on line " + definitionLineNumber + ": " + e.getMessage());
      newException.setStackTrace(e.getStackTrace());
      throw newException;
    }
  }

  /**
   * The context for evaluation within macros. This wraps an existing {@code EvaluationContext}
   * but intercepts reads of the macro's parameters so that they result in a call-by-name evaluation
   * of whatever was passed as the parameter. For example, if you write...
   * <pre>{@code
   * #macro (mymacro $x)
   * $x $x
   * #end
   * #mymacro($foo.bar(23))
   * }</pre>
   * ...then the {@code #mymacro} call will result in {@code $foo.bar(23)} being evaluated twice,
   * once for each time {@code $x} appears. The way this works is that {@code $x} is a <i>thunk</i>.
   * Historically a thunk is a piece of code to evaluate an expression in the context where it
   * occurs, for call-by-name procedures as in Algol 60. Here, it is not exactly a piece of code,
   * but it has the same responsibility.
   */
  static class MacroEvaluationContext implements EvaluationContext {
    private final Map<String, ExpressionNode> parameterThunks;
    private final EvaluationContext originalEvaluationContext;
    private final Node bodyContent;

    MacroEvaluationContext(
        Map<String, ExpressionNode> parameterThunks,
        EvaluationContext originalEvaluationContext,
        Node bodyContent) {
      this.parameterThunks = parameterThunks;
      this.originalEvaluationContext = originalEvaluationContext;
      this.bodyContent = bodyContent;
    }

    @Override
    public Object getVar(String var) {
      if (bodyContent != null && var.equals("bodyContent")) {
        return bodyContent;
      }
      ExpressionNode thunk = parameterThunks.get(var);
      if (thunk == null) {
        return originalEvaluationContext.getVar(var);
      } else {
        // Evaluate the thunk in the context where it appeared, not in this context. Otherwise
        // if you pass $x to a parameter called $x you would get an infinite recursion. Likewise
        // if you had #macro(mymacro $x $y) and a call #mymacro($y 23), you would expect that $x
        // would expand to whatever $y meant at the call site, rather than to the value of the $y
        // parameter.
        return thunk.evaluate(originalEvaluationContext);
      }
    }

    @Override
    public boolean varIsDefined(String var) {
      return parameterThunks.containsKey(var)
          || (bodyContent != null && var.equals("bodyContent"))
          || originalEvaluationContext.varIsDefined(var);
    }

    @Override
    public Runnable setVar(final String var, Object value) {
      // Copy the behaviour that #set will shadow a macro parameter, even though the Velocity peeps
      // seem to agree that that is not good.
      final ExpressionNode thunk = parameterThunks.get(var);
      if (thunk == null) {
        return originalEvaluationContext.setVar(var, value);
      } else {
        parameterThunks.remove(var);
        final Runnable originalUndo = originalEvaluationContext.setVar(var, value);
        return () -> {
          originalUndo.run();
          parameterThunks.put(var, thunk);
        };
      }
    }

    @Override
    public ImmutableSet<Method> publicMethodsWithName(Class<?> startClass, String name) {
      return originalEvaluationContext.publicMethodsWithName(startClass, name);
    }

    @Override
    public Map<String, Macro> getMacros() {
      return originalEvaluationContext.getMacros();
    }
  }
}
