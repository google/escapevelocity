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

import com.google.common.collect.Iterables;
import com.google.escapevelocity.DirectiveNode.SetNode;
import com.google.escapevelocity.Parser.CommentNode;
import java.util.List;

/**
 * Special treatment of spaces before {@code #set} directives.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
final class SetSpacing {
  private SetSpacing() {}

  /**
   * Implements removal of spaces where appropriate before {@code #set} directives. The last element
   * of the given list is removed if it consists of space and if that space occurs in a context
   * where it is removed before {@code #set}. This hack is needed to match what appears to be
   * special treatment in Apache Velocity of spaces before {@code #set} directives. If you have
   * <i>thing</i> <i>horizontal whitespace</i> {@code #set}, then the whitespace is deleted if the
   * <i>thing</i> is a comment ({@code ##...\n}); a reference ({@code $x} or {@code $x.foo} etc); or
   * another {@code #set}. Spaces are also removed before {@code #set} at the start of a macro
   * definition, but that is implemented by calling {@link #removeInitialSpaceBeforeSet}.
   *
   * <p>Newlines are already deleted after a directive or comment, so space will be deleted before
   * the second {@code #set} here:
   *
   * <pre>
   * #set ($x = 17)
   *   #set ($y = 23)
   * </pre>
   *
   * but not here:
   *
   * <pre>
   * #set ($x = 17)
   *
   *   #set ($y = 23)
   * </pre>
   */
  static boolean shouldRemoveLastNodeBeforeSet(List<Node> nodes) {
    if (nodes.isEmpty()) {
      return false;
    }
    Node potentialSpaceBeforeSet = Iterables.getLast(nodes);
    if (nodes.size() == 1) {
      return potentialSpaceBeforeSet.isHorizontalWhitespace();
    }
    Node beforeSpace = nodes.get(nodes.size() - 2);
    if (beforeSpace instanceof ReferenceNode
        || beforeSpace instanceof CommentNode
        || beforeSpace instanceof DirectiveNode) {
      return potentialSpaceBeforeSet.isHorizontalWhitespace();
    }
    return false;
  }

  static List<Node> removeInitialSpaceBeforeSet(List<Node> nodes) {
    if (nodes.size() >= 2
        && nodes.get(0).isHorizontalWhitespace()
        && nodes.get(1) instanceof SetNode) {
      return nodes.subList(1, nodes.size());
    }
    return nodes;
  }
}
