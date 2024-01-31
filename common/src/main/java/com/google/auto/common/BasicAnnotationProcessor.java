/*
 * Copyright 2014 Google LLC
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
package com.google.auto.common;

import static com.google.auto.common.MoreElements.asExecutable;
import static com.google.auto.common.MoreElements.asType;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.auto.common.MoreElements.isType;
import static com.google.auto.common.MoreStreams.toImmutableList;
import static com.google.auto.common.MoreStreams.toImmutableMap;
import static com.google.auto.common.MoreStreams.toImmutableSet;
import static com.google.auto.common.SuperficialValidation.validateElement;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.collect.Multimaps.filterKeys;
import static java.util.Objects.requireNonNull;
import static javax.lang.model.element.ElementKind.CONSTRUCTOR;
import static javax.lang.model.element.ElementKind.METHOD;
import static javax.lang.model.element.ElementKind.PACKAGE;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An abstract {@link Processor} implementation that defers processing of {@link Element}s to later
 * rounds if they cannot be processed.
 *
 * <p>Subclasses put their processing logic in {@link Step} implementations. The steps are passed to
 * the processor by returning them in the {@link #steps()} method, and can access the {@link
 * ProcessingEnvironment} using {@link #processingEnv}.
 *
 * <p>Any logic that needs to happen once per round can be specified by overriding {@link
 * #postRound(RoundEnvironment)}.
 *
 * <h3>Ill-formed elements are deferred</h3>
 *
 * Any annotated element whose nearest enclosing type is not well-formed is deferred, and not passed
 * to any {@code Step}. This helps processors to avoid many common pitfalls, such as {@link
 * ErrorType} instances, {@link ClassCastException}s and badly coerced types.
 *
 * <p>A non-package element is considered well-formed if its type, type parameters, parameters,
 * default values, supertypes, annotations, and enclosed elements are. Package elements are treated
 * similarly, except that their enclosed elements are not validated. See {@link
 * SuperficialValidation#validateElement(Element)} for details.
 *
 * <p>The primary disadvantage to this validation is that any element that forms a circular
 * dependency with a type generated by another {@code BasicAnnotationProcessor} will never compile
 * because the element will never be fully complete. All such compilations will fail with an error
 * message on the offending type that describes the issue.
 *
 * <h3>Each {@code Step} can defer elements</h3>
 *
 * <p>Each {@code Step} can defer elements by including them in the set returned by {@link
 * Step#process(ImmutableSetMultimap)}; elements deferred by a step will be passed back to that step
 * in a later round of processing.
 *
 * <p>This feature is useful when one processor may depend on code generated by another, independent
 * processor, in a way that isn't caught by the well-formedness check described above. For example,
 * if an element {@code A} cannot be processed because processing it depends on the existence of
 * some class {@code B}, then {@code A} should be deferred until a later round of processing, when
 * {@code B} will have been generated by another processor.
 *
 * <p>If {@code A} directly references {@code B}, then the well-formedness check will correctly
 * defer processing of {@code A} until {@code B} has been generated.
 *
 * <p>However, if {@code A} references {@code B} only indirectly (for example, from within a method
 * body), then the well-formedness check will not defer processing {@code A}, but a processing step
 * can reject {@code A}.
 */
public abstract class BasicAnnotationProcessor extends AbstractProcessor {

  /* For every element that is not module/package, to be well-formed its
   * enclosing-type in its entirety should be well-formed. Since modules
   * don't get annotated (and are not supported here) they can be ignored.
   */

  /**
   * Packages and types that have been deferred because either they themselves reference
   * as-yet-undefined types, or at least one of their contained elements does.
   */
  private final Set<ElementFactory> deferredEnclosingElements = new LinkedHashSet<>();

  /**
   * Elements that were explicitly deferred in some {@link Step} by being returned from {@link
   * Step#process}.
   */
  private final SetMultimap<Step, ElementFactory> elementsDeferredBySteps =
      LinkedHashMultimap.create();

  private Elements elementUtils;
  private Messager messager;
  private ImmutableList<? extends Step> steps;

  @Override
  public final synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.elementUtils = processingEnv.getElementUtils();
    this.messager = processingEnv.getMessager();
    this.steps = ImmutableList.copyOf(steps());
  }

  /**
   * Creates {@linkplain ProcessingStep processing steps} for this processor. {@link #processingEnv}
   * is guaranteed to be set when this method is invoked.
   *
   * @deprecated Implement {@link #steps()} instead.
   */
  @Deprecated
  protected Iterable<? extends ProcessingStep> initSteps() {
    throw new AssertionError("If steps() is not implemented, initSteps() must be.");
  }

  /**
   * Creates {@linkplain Step processing steps} for this processor. {@link #processingEnv} is
   * guaranteed to be set when this method is invoked.
   *
   * <p>Note: If you are migrating some steps from {@link ProcessingStep} to {@link Step}, then you
   * can call {@link #asStep(ProcessingStep)} on any unmigrated steps.
   */
  protected Iterable<? extends Step> steps() {
    return Iterables.transform(initSteps(), BasicAnnotationProcessor::asStep);
  }

  /**
   * An optional hook for logic to be executed at the end of each round.
   *
   * @deprecated use {@link #postRound(RoundEnvironment)} instead
   */
  @Deprecated
  protected void postProcess() {}

  /** An optional hook for logic to be executed at the end of each round. */
  protected void postRound(RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver()) {
      postProcess();
    }
  }

  private ImmutableSet<TypeElement> getSupportedAnnotationTypeElements() {
    checkState(steps != null);
    return steps.stream()
        .flatMap(step -> getSupportedAnnotationTypeElements(step).stream())
        .collect(toImmutableSet());
  }

  private ImmutableSet<TypeElement> getSupportedAnnotationTypeElements(Step step) {
    return step.annotations().stream()
        .map(elementUtils::getTypeElement)
        .filter(Objects::nonNull)
        .collect(toImmutableSet());
  }

  /**
   * Returns the set of supported annotation types as collected from registered {@linkplain Step
   * processing steps}.
   */
  @Override
  public final ImmutableSet<String> getSupportedAnnotationTypes() {
    checkState(steps != null);
    return steps.stream().flatMap(step -> step.annotations().stream()).collect(toImmutableSet());
  }

  @Override
  public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    checkState(elementUtils != null);
    checkState(messager != null);
    checkState(steps != null);

    // If this is the last round, report all of the missing elements if there
    // were no errors raised in the round; otherwise reporting the missing
    // elements just adds noise to the output.
    if (roundEnv.processingOver()) {
      postRound(roundEnv);
      if (!roundEnv.errorRaised()) {
        reportMissingElements(
            ImmutableSet.<ElementFactory>builder()
                .addAll(deferredEnclosingElements)
                .addAll(elementsDeferredBySteps.values())
                .build());
      }
      return false;
    }

    process(getWellFormedElementsByAnnotationType(roundEnv));

    postRound(roundEnv);

    return false;
  }

  /** Processes the valid elements, including those previously deferred by each step. */
  private void process(ImmutableSetMultimap<TypeElement, Element> wellFormedElements) {
    for (Step step : steps) {
      ImmutableSet<TypeElement> annotationTypes = getSupportedAnnotationTypeElements(step);
      ImmutableSetMultimap<TypeElement, Element> stepElements =
          new ImmutableSetMultimap.Builder<TypeElement, Element>()
              .putAll(indexByAnnotation(elementsDeferredBySteps.get(step), annotationTypes))
              .putAll(filterKeys(wellFormedElements, annotationTypes::contains))
              .build();
      if (stepElements.isEmpty()) {
        elementsDeferredBySteps.removeAll(step);
      } else {
        Set<? extends Element> rejectedElements =
            step.process(toClassNameKeyedMultimap(stepElements));
        elementsDeferredBySteps.replaceValues(
            step,
            rejectedElements.stream()
                .map(element -> ElementFactory.forAnnotatedElement(element, messager))
                .collect(toImmutableList()));
      }
    }
  }

  private void reportMissingElements(Set<ElementFactory> missingElementFactories) {
    for (ElementFactory missingElementFactory : missingElementFactories) {
      Element missingElement = missingElementFactory.getElement(elementUtils);
      if (missingElement != null) {
        messager.printMessage(
            ERROR,
            processingErrorMessage("this " + Ascii.toLowerCase(missingElement.getKind().name())),
            missingElement);
      } else {
        messager.printMessage(ERROR, processingErrorMessage(missingElementFactory.toString));
      }
    }
  }

  private String processingErrorMessage(String target) {
    return String.format(
        "[%s:MiscError] %s was unable to process %s because not all of its dependencies could be "
            + "resolved. Check for compilation errors or a circular dependency with generated "
            + "code.",
        getClass().getSimpleName(), getClass().getCanonicalName(), target);
  }

  /**
   * Returns the superficially validated annotated elements of this round, including the validated
   * previously ill-formed elements. Also update {@link #deferredEnclosingElements}.
   *
   * <p>Note that the elements deferred by processing steps are guaranteed to be well-formed;
   * therefore, they are ignored (not returned) here, and they will be considered directly in the
   * {@link #process(ImmutableSetMultimap)} method.
   */
  private ImmutableSetMultimap<TypeElement, Element> getWellFormedElementsByAnnotationType(
      RoundEnvironment roundEnv) {
    ImmutableSet<ElementFactory> deferredEnclosingElementsCopy =
        ImmutableSet.copyOf(deferredEnclosingElements);
    deferredEnclosingElements.clear();

    ImmutableSetMultimap.Builder<TypeElement, Element> prevIllFormedElementsBuilder =
        ImmutableSetMultimap.builder();
    for (ElementFactory deferredElementFactory : deferredEnclosingElementsCopy) {
      Element deferredElement = deferredElementFactory.getElement(elementUtils);
      if (deferredElement != null) {
        findAnnotatedElements(
            deferredElement, getSupportedAnnotationTypeElements(), prevIllFormedElementsBuilder);
      } else {
        deferredEnclosingElements.add(deferredElementFactory);
      }
    }

    ImmutableSetMultimap<TypeElement, Element> prevIllFormedElements =
        prevIllFormedElementsBuilder.build();

    ImmutableSetMultimap.Builder<TypeElement, Element> wellFormedElementsBuilder =
        ImmutableSetMultimap.builder();

    // For optimization purposes, the ElementFactory instances for packages and types that have
    // already been verified to be well-formed are stored.
    Set<ElementFactory> wellFormedPackageOrTypeElements = new LinkedHashSet<>();

    /* Look at
     *   1. the previously ill-formed elements which have a present enclosing type (in case of
     *      Package element, the package itself), and
     *   2. the new elements from this round
     * and validate them.
     */
    for (TypeElement annotationType : getSupportedAnnotationTypeElements()) {
      Set<? extends Element> roundElements = roundEnv.getElementsAnnotatedWith(annotationType);

      for (Element element : Sets.union(roundElements, prevIllFormedElements.get(annotationType))) {
        // For every element that is not module/package, to be well-formed its
        // enclosing-type in its entirety should be well-formed. Since modules
        // don't get annotated (and not supported here) they can be ignored.
        Element enclosing = (element.getKind() == PACKAGE) ? element : getEnclosingType(element);
        ElementFactory enclosingFactory = ElementFactory.forAnnotatedElement(enclosing, messager);

        boolean isWellFormedElement =
            wellFormedPackageOrTypeElements.contains(enclosingFactory)
                || (!deferredEnclosingElements.contains(enclosingFactory)
                    && validateElement(enclosing));
        if (isWellFormedElement) {
          wellFormedElementsBuilder.put(annotationType, element);
          wellFormedPackageOrTypeElements.add(enclosingFactory);
        } else {
          deferredEnclosingElements.add(enclosingFactory);
        }
      }
    }

    return wellFormedElementsBuilder.build();
  }

  private ImmutableSetMultimap<TypeElement, Element> indexByAnnotation(
      Set<ElementFactory> annotatedElementFactories, ImmutableSet<TypeElement> annotationTypes) {
    ImmutableSetMultimap.Builder<TypeElement, Element> deferredElementsByAnnotationTypeBuilder =
        ImmutableSetMultimap.builder();
    for (ElementFactory elementFactory : annotatedElementFactories) {
      Element element = elementFactory.getElement(elementUtils);
      if (element != null) {
        for (TypeElement annotationType : annotationTypes) {
          if (isAnnotationPresent(element, annotationType)) {
            deferredElementsByAnnotationTypeBuilder.put(annotationType, element);
          }
        }
      }
    }
    return deferredElementsByAnnotationTypeBuilder.build();
  }

  /**
   * Adds {@code element} and its enclosed elements to {@code annotatedElements} if they are
   * annotated with any annotations in {@code annotationTypes}. Does not traverse to member types of
   * {@code element}, so that if {@code Outer} is passed in the example below, looking for
   * {@code @X}, then {@code Outer}, {@code Outer.foo}, and {@code Outer.foo()} will be added to the
   * multimap, but neither {@code Inner} nor its members will.
   *
   * <pre><code>
   *   {@literal @}X class Outer {
   *     {@literal @}X Object foo;
   *     {@literal @}X void foo() {}
   *     {@literal @}X static class Inner {
   *       {@literal @}X Object bar;
   *       {@literal @}X void bar() {}
   *     }
   *   }
   * </code></pre>
   */
  private static void findAnnotatedElements(
      Element element,
      ImmutableSet<TypeElement> annotationTypes,
      ImmutableSetMultimap.Builder<TypeElement, Element> annotatedElements) {
    for (Element enclosedElement : element.getEnclosedElements()) {
      if (!enclosedElement.getKind().isClass() && !enclosedElement.getKind().isInterface()) {
        findAnnotatedElements(enclosedElement, annotationTypes, annotatedElements);
      }
    }

    // element.getEnclosedElements() does NOT return parameter or type parameter elements

    Parameterizable parameterizable = null;
    if (isType(element)) {
      parameterizable = asType(element);
    } else if (isExecutable(element)) {
      ExecutableElement executableElement = asExecutable(element);
      parameterizable = executableElement;
      for (VariableElement parameterElement : executableElement.getParameters()) {
        findAnnotatedElements(parameterElement, annotationTypes, annotatedElements);
      }
    }
    if (parameterizable != null) {
      for (TypeParameterElement parameterElement : parameterizable.getTypeParameters()) {
        findAnnotatedElements(parameterElement, annotationTypes, annotatedElements);
      }
    }

    for (TypeElement annotationType : annotationTypes) {
      if (isAnnotationPresent(element, annotationType)) {
        annotatedElements.put(annotationType, element);
      }
    }
  }

  /**
   * Returns the nearest enclosing {@link TypeElement} to the current element, throwing an {@link
   * IllegalArgumentException} if the provided {@link Element} is not enclosed by a type.
   */
  // TODO(user) move to MoreElements and make public.
  private static TypeElement getEnclosingType(Element element) {
    Element enclosingTypeElement = element;
    while (enclosingTypeElement != null && !isType(enclosingTypeElement)) {
      enclosingTypeElement = enclosingTypeElement.getEnclosingElement();
    }

    if (enclosingTypeElement == null) {
      throw new IllegalArgumentException(element + " is not enclosed in any TypeElement.");
    }
    return asType(enclosingTypeElement);
  }

  private static ImmutableSetMultimap<String, Element> toClassNameKeyedMultimap(
      SetMultimap<TypeElement, Element> elements) {
    ImmutableSetMultimap.Builder<String, Element> builder = ImmutableSetMultimap.builder();
    elements
        .asMap()
        .forEach(
            (annotation, element) ->
                builder.putAll(annotation.getQualifiedName().toString(), element));
    return builder.build();
  }

  private static boolean isExecutable(Element element) {
    return element.getKind() == METHOD || element.getKind() == CONSTRUCTOR;
  }

  /**
   * Wraps the passed {@link ProcessingStep} in a {@link Step}. This is a convenience method to
   * allow incremental migration to a String-based API. This method can be used to return a not yet
   * converted {@link ProcessingStep} from {@link BasicAnnotationProcessor#steps()}.
   */
  protected static Step asStep(ProcessingStep processingStep) {
    return new ProcessingStepAsStep(processingStep);
  }

  /**
   * The unit of processing logic that runs under the guarantee that all elements are complete and
   * well-formed. A step may reject elements that are not ready for processing but may be at a later
   * round.
   */
  public interface Step {

    /**
     * The set of fully-qualified annotation type names processed by this step.
     *
     * <p>Warning: If the returned names are not names of annotations, they'll be ignored.
     */
    Set<String> annotations();

    /**
     * The implementation of processing logic for the step. It is guaranteed that the keys in {@code
     * elementsByAnnotation} will be a subset of the set returned by {@link #annotations()}.
     *
     * @return the elements (a subset of the values of {@code elementsByAnnotation}) that this step
     *     is unable to process, possibly until a later processing round. These elements will be
     *     passed back to this step at the next round of processing.
     */
    Set<? extends Element> process(ImmutableSetMultimap<String, Element> elementsByAnnotation);
  }

  /**
   * The unit of processing logic that runs under the guarantee that all elements are complete and
   * well-formed. A step may reject elements that are not ready for processing but may be at a later
   * round.
   *
   * @deprecated Implement {@link Step} instead. See {@link BasicAnnotationProcessor#steps()}.
   */
  @Deprecated
  public interface ProcessingStep {

    /** The set of annotation types processed by this step. */
    Set<? extends Class<? extends Annotation>> annotations();

    /**
     * The implementation of processing logic for the step. It is guaranteed that the keys in {@code
     * elementsByAnnotation} will be a subset of the set returned by {@link #annotations()}.
     *
     * @return the elements (a subset of the values of {@code elementsByAnnotation}) that this step
     *     is unable to process, possibly until a later processing round. These elements will be
     *     passed back to this step at the next round of processing.
     */
    Set<? extends Element> process(
        SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation);
  }

  private static class ProcessingStepAsStep implements Step {

    private final ProcessingStep processingStep;
    private final ImmutableMap<String, Class<? extends Annotation>> annotationsByName;

    ProcessingStepAsStep(ProcessingStep processingStep) {
      this.processingStep = processingStep;
      this.annotationsByName =
          processingStep.annotations().stream()
              .collect(
                  toImmutableMap(
                      c -> requireNonNull(c.getCanonicalName()),
                      (Class<? extends Annotation> aClass) -> aClass));
    }

    @Override
    public Set<String> annotations() {
      return annotationsByName.keySet();
    }

    @Override
    public Set<? extends Element> process(
        ImmutableSetMultimap<String, Element> elementsByAnnotation) {
      return processingStep.process(toClassKeyedMultimap(elementsByAnnotation));
    }

    private ImmutableSetMultimap<Class<? extends Annotation>, Element> toClassKeyedMultimap(
        SetMultimap<String, Element> elements) {
      ImmutableSetMultimap.Builder<Class<? extends Annotation>, Element> builder =
          ImmutableSetMultimap.builder();
      elements
          .asMap()
          .forEach(
              (annotationName, annotatedElements) -> {
                Class<? extends Annotation> annotation = annotationsByName.get(annotationName);
                if (annotation != null) { // should not be null
                  builder.putAll(annotation, annotatedElements);
                }
              });
      return builder.build();
    }
  }

  /* Element Factories */

  /**
   * A factory for an annotated element.
   *
   * <p>Instead of saving elements, an {@code ElementFactory} is saved since there is no guarantee
   * that any particular element will always be represented by the same object. (Reference: {@link
   * Element}) For example, Eclipse compiler uses different {@code Element} instances per round. The
   * factory allows us to reconstruct an equivalent element in a later round.
   */
  private abstract static class ElementFactory {
    final String toString;

    private ElementFactory(Element element) {
      this.toString = element.toString();
    }

    /** An {@link ElementFactory} for an annotated element. */
    static ElementFactory forAnnotatedElement(Element element, Messager messager) {
      /* The name of the ElementKind constants is used instead to accommodate for RECORD
       * and RECORD_COMPONENT kinds, which are introduced in Java 16.
       */
      switch (element.getKind().name()) {
        case "PACKAGE":
          return new PackageElementFactory(element);
        case "CLASS":
        case "ENUM":
        case "INTERFACE":
        case "ANNOTATION_TYPE":
        case "RECORD":
          return new TypeElementFactory(element);
        case "TYPE_PARAMETER":
          return new TypeParameterElementFactory(element, messager);
        case "FIELD":
        case "ENUM_CONSTANT":
        case "RECORD_COMPONENT":
          return new FieldOrRecordComponentElementFactory(element);
        case "CONSTRUCTOR":
        case "METHOD":
          return new ExecutableElementFactory(element);
        case "PARAMETER":
          return new ParameterElementFactory(element);
        default:
          messager.printMessage(
              WARNING,
              String.format(
                  "%s does not support element type %s.",
                  ElementFactory.class.getCanonicalName(), element.getKind()));
          return new UnsupportedElementFactory(element);
      }
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      } else if (!(object instanceof ElementFactory)) {
        return false;
      }

      ElementFactory that = (ElementFactory) object;
      return this.toString.equals(that.toString);
    }

    @Override
    public int hashCode() {
      return toString.hashCode();
    }

    /**
     * Returns the {@link Element} corresponding to the name information saved in this factory, or
     * null if none exists.
     */
    abstract @Nullable Element getElement(Elements elementUtils);
  }

  /**
   * Saves the Element reference and returns it when inquired, with the hope that the same object
   * still represents that element, or the required information is present.
   */
  private static final class UnsupportedElementFactory extends ElementFactory {
    private final Element element;

    private UnsupportedElementFactory(Element element) {
      super(element);
      this.element = element;
    }

    @Override
    Element getElement(Elements elementUtils) {
      return element;
    }
  }

  /* It's unfortunate that we have to track types and packages separately, but since there are
   * two different methods to look them up in {@link Elements}, we end up with a lot of parallel
   * logic. :(
   */
  private static final class PackageElementFactory extends ElementFactory {
    private PackageElementFactory(Element element) {
      super(element);
    }

    @Override
    @Nullable PackageElement getElement(Elements elementUtils) {
      return elementUtils.getPackageElement(toString);
    }
  }

  private static final class TypeElementFactory extends ElementFactory {
    private TypeElementFactory(Element element) {
      super(element);
    }

    @Override
    @Nullable TypeElement getElement(Elements elementUtils) {
      return elementUtils.getTypeElement(toString);
    }
  }

  private static final class TypeParameterElementFactory extends ElementFactory {
    private final ElementFactory enclosingElementFactory;

    private TypeParameterElementFactory(Element element, Messager messager) {
      super(element);
      this.enclosingElementFactory =
          ElementFactory.forAnnotatedElement(element.getEnclosingElement(), messager);
    }

    @Override
    @Nullable TypeParameterElement getElement(Elements elementUtils) {
      Parameterizable enclosingElement =
          (Parameterizable) enclosingElementFactory.getElement(elementUtils);
      if (enclosingElement == null) {
        return null;
      }
      return enclosingElement.getTypeParameters().stream()
          .filter(typeParamElement -> toString.equals(typeParamElement.toString()))
          .collect(onlyElement());
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      } else if (!(object instanceof TypeParameterElementFactory)) {
        return false;
      }

      TypeParameterElementFactory that = (TypeParameterElementFactory) object;
      return this.toString.equals(that.toString)
          && this.enclosingElementFactory.equals(that.enclosingElementFactory);
    }

    @Override
    public int hashCode() {
      return Objects.hash(toString, enclosingElementFactory);
    }
  }

  /** Represents FIELD, ENUM_CONSTANT, and RECORD_COMPONENT */
  private static class FieldOrRecordComponentElementFactory extends ElementFactory {
    private final TypeElementFactory enclosingTypeElementFactory;
    private final ElementKind elementKind;

    private FieldOrRecordComponentElementFactory(Element element) {
      super(element); // toString is its simple name.
      this.enclosingTypeElementFactory = new TypeElementFactory(getEnclosingType(element));
      this.elementKind = element.getKind();
    }

    @Override
    @Nullable Element getElement(Elements elementUtils) {
      TypeElement enclosingTypeElement = enclosingTypeElementFactory.getElement(elementUtils);
      if (enclosingTypeElement == null) {
        return null;
      }
      return enclosingTypeElement.getEnclosedElements().stream()
          .filter(
              element ->
                  elementKind.equals(element.getKind()) && toString.equals(element.toString()))
          .collect(onlyElement());
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (!super.equals(object) || !(object instanceof FieldOrRecordComponentElementFactory)) {
        return false;
      }
      // To distinguish between a field and record_component
      FieldOrRecordComponentElementFactory that = (FieldOrRecordComponentElementFactory) object;
      return this.elementKind == that.elementKind;
    }

    @Override
    public int hashCode() {
      return Objects.hash(toString, elementKind);
    }
  }

  /**
   * Represents METHOD and CONSTRUCTOR.
   *
   * <p>The {@code equals()} and {@code hashCode()} have been overridden since the {@code toString}
   * alone is not sufficient to make a distinction in all overloaded cases. For example, {@code <C
   * extends Set<String>> void m(C c) {}}, and {@code <C extends SortedSet<String>> void m(C c) {}}
   * both have the same toString {@code <C>m(C)} but are valid cases for overloading methods.
   * Moreover, the needed enclosing type-element information is not included in the toString.
   *
   * <p>The executable element is retrieved by saving its enclosing type element, simple name, and
   * ordinal position in the source code relative to other related overloaded methods, meaning those
   * with the same simple name. This is possible because according to Java language specification
   * for {@link TypeElement#getEnclosedElements()}: "As a particular instance of the general
   * accuracy requirements and the ordering behavior required of this interface, the list of
   * enclosed elements will be returned to the natural order for the originating source of
   * information about the type. For example, if the information about the type is originating from
   * a source file, the elements will be returned in source code order. (However, in that case the
   * ordering of implicitly declared elements, such as default constructors, is not specified.)"
   *
   * <p>Simple name is saved since comparing the toString is not reliable when at least one
   * parameter references ERROR, possibly because it is not generated yet. For example, method
   * {@code void m(SomeGeneratedClass sgc)}, before the generation of {@code SomeGeneratedClass} has
   * the toString {@code m(SomeGeneratedClass)}; however, after the generation it will have toString
   * equal to {@code m(test.SomeGeneratedClass)} assuming that the package name is "test".
   */
  private static final class ExecutableElementFactory extends ElementFactory {
    private final TypeElementFactory enclosingTypeElementFactory;
    private final Name simpleName;

    /**
     * The index of the element among all elements of the same kind within the enclosing type. If
     * this is method {@code foo(...)} and the index is 0, that means that the method is the first
     * method called {@code foo} in the enclosing type.
     */
    private final int sameNameIndex;

    private ExecutableElementFactory(Element element) {
      super(element);
      TypeElement enclosingTypeElement = getEnclosingType(element);
      this.enclosingTypeElementFactory = new TypeElementFactory(enclosingTypeElement);
      this.simpleName = element.getSimpleName();

      ImmutableList<Element> methods = sameNameMethods(enclosingTypeElement, simpleName);
      this.sameNameIndex = methods.indexOf(element);
      checkState(this.sameNameIndex >= 0, "Did not find %s in %s", element, methods);
    }

    @Override
    @Nullable ExecutableElement getElement(Elements elementUtils) {
      TypeElement enclosingTypeElement = enclosingTypeElementFactory.getElement(elementUtils);
      if (enclosingTypeElement == null) {
        return null;
      }
      ImmutableList<Element> methods = sameNameMethods(enclosingTypeElement, simpleName);
      return asExecutable(methods.get(sameNameIndex));
    }

    private static ImmutableList<Element> sameNameMethods(
        TypeElement enclosingTypeElement, Name simpleName) {
      return enclosingTypeElement.getEnclosedElements().stream()
          .filter(element -> element.getSimpleName().equals(simpleName) && isExecutable(element))
          .collect(toImmutableList());
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      } else if (!(object instanceof ExecutableElementFactory)) {
        return false;
      }

      ExecutableElementFactory that = (ExecutableElementFactory) object;
      return this.simpleName.equals(that.simpleName)
          && this.sameNameIndex == that.sameNameIndex
          && this.enclosingTypeElementFactory.equals(that.enclosingTypeElementFactory);
    }

    @Override
    public int hashCode() {
      return Objects.hash(simpleName, sameNameIndex, enclosingTypeElementFactory);
    }
  }

  private static final class ParameterElementFactory extends ElementFactory {
    private final ExecutableElementFactory enclosingExecutableElementFactory;

    private ParameterElementFactory(Element element) {
      super(element);
      this.enclosingExecutableElementFactory =
          new ExecutableElementFactory(element.getEnclosingElement());
    }

    @Override
    @Nullable VariableElement getElement(Elements elementUtils) {
      ExecutableElement enclosingExecutableElement =
          enclosingExecutableElementFactory.getElement(elementUtils);
      if (enclosingExecutableElement == null) {
        return null;
      } else {
        return enclosingExecutableElement.getParameters().stream()
            .filter(paramElement -> toString.equals(paramElement.toString()))
            .collect(onlyElement());
      }
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      } else if (!(object instanceof ParameterElementFactory)) {
        return false;
      }

      ParameterElementFactory that = (ParameterElementFactory) object;
      return this.toString.equals(that.toString)
          && this.enclosingExecutableElementFactory.equals(that.enclosingExecutableElementFactory);
    }

    @Override
    public int hashCode() {
      return Objects.hash(toString, enclosingExecutableElementFactory);
    }
  }
}
