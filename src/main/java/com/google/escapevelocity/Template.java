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

import com.google.escapevelocity.EvaluationContext.PlainEvaluationContext;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * A template expressed in EscapeVelocity, a subset of the Velocity Template Language (VTL) from
 * Apache. The intent of this implementation is that if a template is accepted and successfully
 * produces output, that output will be identical to what Velocity would have produced for the same
 * template and input variables.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
// TODO(emcmanus): spell out exactly what Velocity features are unsupported.
public class Template {
  private final Node root;
  
  /**
   * Caches {@link Method} objects for public methods accessed through references. The first time
   * we evaluate {@code $var.property} or {@code $var.method(...)} for a {@code $var} of a given
   * class and for a given property or method signature, we'll store the resultant {@link Method}
   * object. Every subsequent time we'll reuse that {@link Method}. The method lookup is quite slow
   * so caching is useful. The main downside is that we may potentially hold on to {@link Method}
   * objects that will never be used with this {@link Template} again. But in practice templates
   * tend to be used repeatedly with the same classes.
   */
  private final MethodFinder methodFinder = new MethodFinder();

  /**
   * Used to resolve references to resources in the template, through {@code #parse} directives.
   *
   * <p>Here is an example that opens nested templates as resources relative to the calling class:
   *
   * <pre>{@code
   *   ResourceOpener resourceOpener = resourceName -> {
   *     InputStream inputStream = getClass().getResource(resourceName);
   *     if (inputStream == null) {
   *       throw new IOException("Unknown resource: " + resourceName);
   *     }
   *     return new BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8));
   *   };
   * }</pre>
   */
  @FunctionalInterface
  public interface ResourceOpener {

    /**
     * Returns a {@code Reader} that will be used to read the given resource, then closed.
     *
     * @param resourceName the name of the resource to be read. This will never be null.
     * @return a {@code Reader} for the resource.
     * @throws IOException if the resource cannot be opened.
     */
    Reader openResource(String resourceName) throws IOException;
  }

  /**
   * Parses a VTL template from the given {@code Reader}. The template cannot reference other
   * templates (for example with {@code #parse}). For that, use
   * {@link #parseFrom(String, ResourceOpener)}.
   *
   * @param reader a Reader that will supply the text of the template. It will be closed on return
   *     from this method.
   * @return an object representing the parsed template.
   * @throws IOException if there is an exception reading from {@code reader}, or if the template
   *     references another template via {@code #parse}.
   */
  public static Template parseFrom(Reader reader) throws IOException {
    ResourceOpener resourceOpener = resourceName -> {
      if (resourceName == null) {
        return reader;
      } else {
        throw new IOException("No ResourceOpener has been configured to read " + resourceName);
      }
    };
    try {
      return parseFrom((String) null, resourceOpener);
    } finally {
      reader.close();
    }
  }

  /**
   * Parse a VTL template of the given name using the given {@code ResourceOpener}.
   *
   * @param resourceName name of the resource. May be null.
   * @param resourceOpener used to open the initial resource and resources referenced by
   *     {@code #parse} directives in the template.
   * @return an object representing the parsed template.
   * @throws IOException if there is an exception opening or reading from any resource.
   */
  public static Template parseFrom(
      String resourceName, ResourceOpener resourceOpener) throws IOException {
    try (Reader reader = resourceOpener.openResource(resourceName)) {
      return new Parser(reader, resourceName, resourceOpener).parse();
    }
  }

  Template(Node root) {
    this.root = root;
  }

  /**
   * Evaluate the given template with the given initial set of variables.
   *
   * @param vars a map where the keys are variable names and the values are the corresponding
   *     variable values. For example, if {@code "x"} maps to 23, then {@code $x} in the template
   *     will expand to 23.
   *
   * @return the string result of evaluating the template.
   */
  public String evaluate(Map<String, ?> vars) {
    EvaluationContext evaluationContext = new PlainEvaluationContext(vars, methodFinder);
    return String.valueOf(root.evaluate(evaluationContext));
  }
}
