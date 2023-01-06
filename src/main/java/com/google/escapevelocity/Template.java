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

import com.google.common.collect.ImmutableMap;
import com.google.escapevelocity.EvaluationContext.PlainEvaluationContext;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

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
   * Macros that are defined in this template (this exact VTL file). If the template includes
   * {@code #parse} directives, those might end up defining other macros when a {@code #parse} is
   * evaluated. The {@code #parse} produces a separate {@code Template} object with its own
   * {@code macros} map. When the root {@code Template} is evaluated, the {@link EvaluationContext}
   * starts off with the macros here, and each {@code #parse} that is executed may add macros to the
   * map in the {@code EvaluationContext}.
   */
  private final ImmutableMap<String, Macro> macros;

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
   *     return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
   *   };
   *   Template template = Template.parseFrom("foo.vm", resourceOpener);
   * }</pre>
   */
  @FunctionalInterface
  public interface ResourceOpener {

    /**
     * Returns a {@code Reader} that will be used to read the given resource, then closed. The
     * caller of this method will perform its own buffering (via {@link java.io.BufferedReader
     * BufferedReader}), so the returned Reader doesn't need to be buffered.
     *
     * @param resourceName the name of the resource to be read. This can be null if {@code
     *     Template.parseFrom} is called with a null {@code resourceName}.
     * @return a {@code Reader} for the resource.
     * @throws IOException if the resource cannot be opened.
     */
    Reader openResource(String resourceName) throws IOException;
  }

  /**
   * Parses a VTL template from the given {@code Reader}. The template cannot reference other
   * templates (for example with {@code #parse}). For that, use {@link #parseFrom(String,
   * ResourceOpener)}.
   *
   * @param reader a Reader that will supply the text of the template. It will be closed on return
   *     from this method. The Reader will be buffered internally by this method (via {@link
   *     java.io.BufferedReader BufferedReader}), so the passed-in Reader doesn't need to perform
   *     its own buffering.
   * @return an object representing the parsed template.
   * @throws IOException if there is an exception reading from {@code reader}, or if the template
   *     references another template via {@code #parse}.
   * @throws ParseException if the text of the template could not be parsed.
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
   * Parses a VTL template of the given name using the given {@code ResourceOpener}.
   *
   * @param resourceName name of the resource. May be null.
   * @param resourceOpener used to open the initial resource and resources referenced by
   *     {@code #parse} directives in the template.
   * @return an object representing the parsed template.
   * @throws IOException if there is an exception opening or reading from any resource.
   * @throws ParseException if the text of the template could not be parsed.
   */
  public static Template parseFrom(
      String resourceName, ResourceOpener resourceOpener) throws IOException {

    // This cache is passed into the top-level parser, and saved in the ParseNode for any #parse
    // directive. When a #parse is evaluated, it either finds the already-parsed Template for the
    // resource named in its argument, or it parses the resource and saves the result in this
    // cache. If it parses the resource, it will pass in the same parseCache to the parseFrom method
    // below so the parseCache will be shared by any #parse directives in nested templates.
    Map<String, Template> parseCache = new TreeMap<>();

    return parseFrom(resourceName, resourceOpener, parseCache);
  }

  static Template parseFrom(
      String resourceName, ResourceOpener resourceOpener, Map<String, Template> parseCache)
      throws IOException {
    try (Reader reader = resourceOpener.openResource(resourceName)) {
      return new Parser(reader, resourceName, resourceOpener, parseCache).parse();
    }
  }

  Template(Node root, ImmutableMap<String, Macro> macros) {
    this.root = root;
    this.macros = macros;
  }

  /**
   * Evaluate the given template with the given initial set of variables.
   *
   * @param vars a map where the keys are variable names and the values are the corresponding
   *     variable values. For example, if {@code "x"} maps to 23, then {@code $x} in the template
   *     will expand to 23.
   * @return the string result of evaluating the template.
   * @throws EvaluationException if the evaluation failed, for example because of an undefined
   *     reference. If the template contains a {@code #parse} directive, there may be an exception
   *     such as {@link ParseException} or {@link IOException} when the nested template is read and
   *     parsed. That exception will then be the {@linkplain Throwable#getCause() cause} of an
   *     {@link EvaluationException}.
   */
  public String evaluate(Map<String, ?> vars) {
    // This is so that a nested #parse can define new macros. Obviously that shouldn't affect the
    // macros stored in the template, since later calls to `evaluate` should not see changes.
    Map<String, Macro> modifiableMacros = new LinkedHashMap<>(macros);
    EvaluationContext evaluationContext =
        new PlainEvaluationContext(vars, modifiableMacros, methodFinder);
    StringBuilder output = new StringBuilder(1024);
    // The default size of 16 is going to be too small for the vast majority of rendered templates.
    // We use a somewhat arbitrary larger starting size instead.
    render(evaluationContext, output);
    return output.toString();
  }

  void render(EvaluationContext context, StringBuilder output) {
    root.render(context, output);
  }

  ImmutableMap<String, Macro> getMacros() {
    return macros;
  }
}
