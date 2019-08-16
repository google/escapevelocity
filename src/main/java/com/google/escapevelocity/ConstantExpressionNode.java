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

import com.google.common.base.CharMatcher;

/**
 * A node in the parse tree representing a constant value. Evaluating the node yields the constant
 * value. Instances of this class are used both in expressions, like the {@code 23} in
 * {@code #set ($x = 23)}, and for literal text in templates. In the template...
 * <pre>{@code
 * abc#{if}($x == 5)def#{end}xyz
 * }</pre>
 * ...each of the strings {@code abc}, {@code def}, {@code xyz} is represented by an instance of
 * this class that {@linkplain #evaluate evaluates} to that string, and the value {@code 5} is
 * represented by an instance of this class that evaluates to the integer 5.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class ConstantExpressionNode extends ExpressionNode {
  private final Object value;

  ConstantExpressionNode(String resourceName, int lineNumber, Object value) {
    super(resourceName, lineNumber);
    this.value = value;
  }

  @Override
  Object evaluate(EvaluationContext context) {
    return value;
  }

  @Override
  boolean isWhitespace() {
    return value instanceof String && CharMatcher.whitespace().matchesAllOf((String) value);
  }
}
