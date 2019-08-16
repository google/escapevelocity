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

/**
 * A node in the parse tree.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class Node {
  final String resourceName;
  final int lineNumber;

  Node(String resourceName, int lineNumber) {
    this.resourceName = resourceName;
    this.lineNumber = lineNumber;
  }

  /**
   * Adds this node's contribution to the {@code output}. Depending on the node type, this might be
   * plain text from the template, or one branch of an {@code #if}, or the value of a
   * {@code $reference}, etc.
   */
  abstract void render(EvaluationContext context, StringBuilder output);

  /** True if this node is just a span of whitespace in the text. */
  boolean isWhitespace() {
    return false;
  }

  private String where() {
    String where = "In expression on line " + lineNumber;
    if (resourceName != null) {
      where += " of " + resourceName;
    }
    return where;
  }

  EvaluationException evaluationException(String message) {
    return new EvaluationException(where() + ": " + message);
  }

  EvaluationException evaluationException(Throwable cause) {
    return new EvaluationException(where() + ": " + cause, cause);
  }

  /**
   * Returns an empty node in the parse tree. This is used for example to represent the trivial
   * "else" part of an {@code #if} that does not have an explicit {@code #else}.
   */
  static Node emptyNode(String resourceName, int lineNumber) {
    return new Cons(resourceName, lineNumber, ImmutableList.<Node>of());
  }

  /**
   * Create a new parse tree node that is the concatenation of the given ones. Evaluating the
   * new node produces the same string as evaluating each of the given nodes and concatenating the
   * result.
   */
  static Node cons(String resourceName, int lineNumber, ImmutableList<Node> nodes) {
    return new Cons(resourceName, lineNumber, nodes);
  }

  private static final class Cons extends Node {
    private final ImmutableList<Node> nodes;

    Cons(String resourceName, int lineNumber, ImmutableList<Node> nodes) {
      super(resourceName, lineNumber);
      this.nodes = nodes;
    }

    @Override
    void render(EvaluationContext context, StringBuilder output) {
      for (Node node : nodes) {
        node.render(context, output);
      }
    }
  }
}
