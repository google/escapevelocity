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

/**
 * A parsing node that represents the end of a span, such as the {@code #end} after the body of a
 * {@code #foreach} or an {@code #else} in an {@code #if}. The end of the file is also one of these.
 *
 * <p>These nodes are used during parsing but do not end up in the final parse tree.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class StopNode extends Node {
  StopNode(String resourceName, int lineNumber) {
    super(resourceName, lineNumber);
  }

  /**
   * This method always throws an exception because a node like this should never be found in the
   * final parse tree.
   */
  @Override
  void render(EvaluationContext context, StringBuilder output) {
    throw new UnsupportedOperationException(getClass().getName());
  }

  /**
   * The name of the token, for use in parse error messages.
   */
  abstract String name();

  /**
   * A synthetic node that represents the end of the input. This node is the last one in the
   * initial token string and also the last one in the parse tree.
   */
  static final class EofNode extends StopNode {
    EofNode(String resourceName, int lineNumber) {
      super(resourceName, lineNumber);
    }

    @Override
    String name() {
      return "end of file";
    }
  }

  static final class EndNode extends StopNode {
    EndNode(String resourceName, int lineNumber) {
      super(resourceName, lineNumber);
    }

    @Override String name() {
      return "#end";
    }
  }

  static final class ElseIfNode extends StopNode {
    ElseIfNode(String resourceName, int lineNumber) {
      super(resourceName, lineNumber);
    }

    @Override String name() {
      return "#elseif";
    }
  }

  static final class ElseNode extends StopNode {
    ElseNode(String resourceName, int lineNumber) {
      super(resourceName, lineNumber);
    }

    @Override String name() {
      return "#else";
    }
  }
}
