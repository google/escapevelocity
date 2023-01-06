/*
 * Copyright (C) 2023 Google, Inc.
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

import com.google.escapevelocity.Template.ResourceOpener;
import java.io.IOException;
import java.util.Map;

/**
 * Representation of a {@code #parse} directive in the parse tree.
 *
 * <p>A {@code #parse (<primary>)} directive does nothing until the template is evaluated. Then the
 * {@code <primary>} should evaluate to a string, which will be used to derive a nested {@link
 * Template}. If the string was seen before (in this evaluation, or an earlier evaluation) then the
 * {@code Template} will be retrieved from the {@code parseCache}. Otherwise we will get a {@link
 * Reader} from the {@link ResourceOpener} and parse its contents to produce a new {@code Template},
 * which we will record in the {@code parseCache}. Either way, we will execute the nested {@code
 * Template}, which means adding its macros to the {@link EvaluationContext} and evaluating it to
 * add text to the final output.
 */
class ParseNode extends Node {
  private final ExpressionNode nestedResourceNameExpression;
  private final ResourceOpener resourceOpener;
  private final Map<String, Template> parseCache;

  ParseNode(
      String resourceName,
      int startLine,
      ExpressionNode nestedResourceNameExpression,
      ResourceOpener resourceOpener,
      Map<String, Template> parseCache) {
    super(resourceName, startLine);
    this.nestedResourceNameExpression = nestedResourceNameExpression;
    this.resourceOpener = resourceOpener;
    this.parseCache = parseCache;
  }

  @Override
  void render(EvaluationContext context, StringBuilder output) {
    Object resourceNameObject = nestedResourceNameExpression.evaluate(context);
    if (!(resourceNameObject instanceof String)) {
      String what = resourceNameObject == null ? "null" : resourceNameObject.getClass().getName();
      throw evaluationException("Argument to #parse must be a string, not " + what);
    }
    String resourceName = (String) resourceNameObject;
    Template template = parseCache.get(resourceName);
    if (template == null) {
      try {
        template = Template.parseFrom(resourceName, resourceOpener, parseCache);
        parseCache.put(resourceName, template);
      } catch (ParseException | IOException e) {
        throw evaluationException(e);
      }
    }

    // Lift macros from the nested template into the evaluation context so we can evaluate the
    // nested template with the pre-processed macros available. The macros will also be available in
    // the calling template after the #parse.
    template.getMacros().forEach((name, macro) -> context.getMacros().putIfAbsent(name, macro));
    try {
      template.render(context, output);
    } catch (BreakException e) {
      if (e.forEachScope()) { // this isn't for us
        throw e;
      }
      // OK: the #break has broken out of this #parse
    }
  }
}
