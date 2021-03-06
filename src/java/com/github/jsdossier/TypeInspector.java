/*
 Copyright 2013-2016 Jason Leyba

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.github.jsdossier;

import static com.github.jsdossier.Comments.isVacuousTypeComment;
import static com.github.jsdossier.jscomp.Types.isBuiltInFunctionProperty;
import static com.github.jsdossier.jscomp.Types.isConstructorTypeDefinition;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.transform;

import com.github.jsdossier.annotations.TypeFilter;
import com.github.jsdossier.jscomp.JsDoc;
import com.github.jsdossier.jscomp.JsDoc.Annotation;
import com.github.jsdossier.jscomp.JsDoc.TypedDescription;
import com.github.jsdossier.jscomp.Module;
import com.github.jsdossier.jscomp.NominalType;
import com.github.jsdossier.jscomp.Parameter;
import com.github.jsdossier.jscomp.TypeRegistry;
import com.github.jsdossier.proto.BaseProperty;
import com.github.jsdossier.proto.Comment;
import com.github.jsdossier.proto.Function;
import com.github.jsdossier.proto.Function.Detail;
import com.github.jsdossier.proto.TypeLink;
import com.github.jsdossier.proto.Visibility;
import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticSourceFile;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.Property;
import com.google.javascript.rhino.jstype.TemplatizedType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Extracts on the functions and properties in a type suitable for injection into a Soy template.
 */
@AutoFactory
final class TypeInspector {

  private static final Pattern SUMMARY_REGEX = Pattern.compile("(.*?\\.)[\\s$]", Pattern.DOTALL);

  /**
   * URI pattern from RFC 2396, with the scheme portion restricted to http/s.
   */
  private static final Pattern URI_PATTERN = Pattern.compile(
      "^\\s*https?://" +
          "(?<authority>[^/?#]+)" +
          "(?<path>/[^?#]*)?" +
          "(?<query>\\?[^#]*)?" +
          "(?<fragment>#.*)?" +
          "\\s*$",
      Pattern.CASE_INSENSITIVE);

  private final DossierFileSystem dfs;
  private final CommentParser parser;
  private final TypeRegistry registry;
  private final JSTypeRegistry jsRegistry;
  private final Predicate<String> typeFilter;
  private final TypeExpressionParserFactory expressionParserFactory;
  private final LinkFactory linkFactory;
  private final NominalType inspectedType;

  TypeInspector(
      @Provided DossierFileSystem dfs,
      @Provided CommentParser parser,
      @Provided TypeRegistry registry,
      @Provided JSTypeRegistry jsRegistry,
      @Provided @TypeFilter Predicate<String> typeFilter,
      @Provided TypeExpressionParserFactory expressionParserFactory,
      @Provided LinkFactoryBuilder linkFactoryBuilder,
      NominalType inspectedType) {
    this.dfs = dfs;
    this.parser = parser;
    this.registry = registry;
    this.jsRegistry = jsRegistry;
    this.expressionParserFactory = expressionParserFactory;
    this.typeFilter = typeFilter;
    this.linkFactory = linkFactoryBuilder.create(inspectedType);
    this.inspectedType = inspectedType;
  }

  /**
   * Returns the top description for the inspected type.
   */
  public Comment getTypeDescription() {
    return getTypeDescription(inspectedType, false);
  }

  /**
   * Extracts the summary sentence for the inspected type. This is the substring up to the
   * first period (.) followed by a blank, tab, or newline.
   */
  public Comment getTypeSummary() {
    return getTypeDescription(inspectedType, true);
  }

  /**
   * Returns the description for the given type. All links in the returned comment will be
   * generated relative to the output file for this instance's inspected type.
   *
   * @param type the type to extract a description from.
   * @param summaryOnly whether to only extract a summary description. The summary is substring
   *     up to the first period (.) followed by a blank, tab, or newline. Summaries are extracted
   *     before the markdown parser is invoked.
   * @return the extracted description.
   */
  public Comment getTypeDescription(NominalType type, boolean summaryOnly) {
    String blockComment = type.getJsDoc().getBlockComment();
    if (!isNullOrEmpty(blockComment)) {
      return parseComment(type, blockComment, summaryOnly);
    }

    if (type.isModuleExports()) {
      Module module = type.getModule().get();
      blockComment = module.getJsDoc().getBlockComment();
      if (!isNullOrEmpty(blockComment)) {
        return parseComment(type, blockComment, summaryOnly);
      }
    }

    if (type.getModule().isPresent() && !type.isModuleExports()) {
      Module module = type.getModule().get();
      String exportedName = type.getName().substring(module.getId().length() + 1);
      String internalName = module.getExportedNames().get(exportedName);
      if (!isNullOrEmpty(internalName)) {
        JSDocInfo info = module.getInternalVarDocs().get(internalName);
        blockComment = JsDoc.from(info).getBlockComment();

        if (isNullOrEmpty(blockComment)) {
          NominalType resolved = linkFactory.getTypeContext()
              .changeContext(type)
              .resolveType(internalName);
          if (resolved != null) {
            blockComment = resolved.getJsDoc().getBlockComment();
            if (!isNullOrEmpty(blockComment)) {
              return parseComment(resolved, blockComment, summaryOnly);
            }
          }
        } else {
          return parseComment(type, blockComment, summaryOnly);
        }
      }
    }

    NominalType aliased = registry.getTypes(type.getType()).get(0);
    if (aliased != null && aliased != type) {
      return getTypeDescription(aliased, summaryOnly);
    }

    return Comment.getDefaultInstance();
  }

  private Comment parseComment(NominalType context, String comment, boolean summaryOnly) {
    if (summaryOnly) {
      Matcher matcher = SUMMARY_REGEX.matcher(comment);
      if (matcher.find()) {
        comment = matcher.group(1);
      }
    }
    return parser.parseComment(comment, linkFactory.withTypeContext(context));
  }

  /**
   * Extracts information on the properties defined directly on the given nominal type. For
   * classes and interfaces, this will return information on the <em>static</em> properties, not
   * instance properties.
   */
  public Report inspectType() {
    List<Property> properties = getProperties(inspectedType);
    Collections.sort(properties, new PropertyNameComparator());
    if (properties.isEmpty()) {
      return new Report();
    }

    Report report = new Report();

    for (Property property : properties) {
      String name = property.getName();
      if (!inspectedType.isModuleExports() && !inspectedType.isNamespace()) {
        String typeName = dfs.getDisplayName(inspectedType);
        int index = typeName.lastIndexOf('.');
        if (index != -1) {
          typeName = typeName.substring(index + 1);
        }
        name = typeName + "." + name;
      }

      PropertyDocs docs = findStaticPropertyJsDoc(inspectedType, property);
      JsDoc jsdoc = docs.getJsDoc();

      if (jsdoc.getVisibility() == JSDocInfo.Visibility.PRIVATE
          || (name.endsWith(".superClass_") && property.getType().isFunctionPrototypeType())) {
        continue;
      }

      if (jsdoc.isDefine()) {
        name = dfs.getQualifiedDisplayName(inspectedType) + "." + property.getName();
        report.addCompilerConstant(getPropertyData(
            name,
            property.getType(),
            property.getNode(),
            docs));

      } else if (property.getType().isFunctionType()) {
        report.addFunction(getFunctionData(
            name,
            property.getType(),
            property.getNode(),
            docs));

      } else if (!property.getType().isEnumElementType()) {
        report.addProperty(getPropertyData(
            name,
            property.getType(),
            property.getNode(),
            docs));
      }
    }

    return report;
  }

  private PropertyDocs findStaticPropertyJsDoc(NominalType ownerType, Property property) {
    JsDoc jsdoc = JsDoc.from(property.getJSDocInfo());
    if (!isEmptyComment(jsdoc) || !ownerType.isModuleExports()) {
      return PropertyDocs.create(ownerType, jsdoc);
    }

    // The property does not have any docs, but is part of a module's exported API,
    // so we can see if the property is just a symbol defined in the module whose docs we can
    // use.
    Module module = ownerType.getModule().get();

    String internalName = module.getExportedNames().get(property.getName());
    if (isNullOrEmpty(internalName)) {
      return PropertyDocs.create(ownerType, jsdoc);
    }

    // Case 1: the exported property is a reference to a variable defiend within the module that
    // had documentation:
    //    /** hi */
    //    let someSymbol = function() {};
    //    export {someSymbol as publicName};
    jsdoc = JsDoc.from(module.getInternalVarDocs().get(internalName));
    if (!isEmptyComment(jsdoc)) {
      return PropertyDocs.create(ownerType, jsdoc);
    }

    // The internal name is an alias for a symbol renamed during compilation, so we should use the
    // resolved name for subsequent checks.
    String resolved = registry.resolveAlias(ownerType, internalName);
    if (resolved != null) {
      internalName = resolved;
    }

    // The exported property is a reference to another type recorded from the global scope.
    if (registry.isType(internalName)) {
      // We ignore this case if the forwarded type is the main exports of another module.
      if (registry.isModule(internalName)) {
        return PropertyDocs.create(ownerType, jsdoc);
      }
      NominalType type = registry.getType(internalName);
      return PropertyDocs.create(type, type.getJsDoc());
    }

    // Make one last attempt when the property is a reference to another static property.
    int index = internalName.indexOf('.');
    if (index != -1) {
      String name = internalName.substring(0, index);
      if (registry.isType(name)) {
        NominalType type = registry.getType(name);
        property = type.getType().toObjectType().getOwnSlot(
            internalName.substring(index + 1));
        if (property != null) {
          return findStaticPropertyJsDoc(type, property);
        }
      }
    }

    return PropertyDocs.create(ownerType, jsdoc);
  }

  private static boolean isEmptyComment(JsDoc doc) {
    return isNullOrEmpty(doc.getOriginalCommentString());
  }

  /**
   * Returns the raw properties for the given type.
   */
  public List<Property> getProperties(NominalType nominalType) {
    JSType type = nominalType.getType();
    ObjectType object = ObjectType.cast(type);
    if (object == null) {
      return ImmutableList.of();
    }

    List<Property> properties = new ArrayList<>();
    for (String name : object.getOwnPropertyNames()) {
      Property property = null;
      if (type.isFunctionType()) {
        if (!isBuiltInFunctionProperty(type, name)) {
          property = object.getOwnSlot(name);
        }
      } else if (!"prototype".equals(name)) {
        property = object.getOwnSlot(name);
      }

      if (property != null) {
        if (property.getType().isConstructor()
            && isConstructorTypeDefinition(
                property.getType(), JsDoc.from(property.getJSDocInfo()))) {
          continue;
        }

        // If the property is another module and the inspected type is also a module, then count
        // the property as a static property. Otherwise, if the property i registered as a nominal
        // type, it does not count as a static property. It should also be ignored if it is not
        // registered as a nominal type, but its qualified name has been filtered out by the user.
        String qualifiedName = nominalType.getName() + "." + property.getName();
        if (((inspectedType.isModuleExports() && registry.isModule(property.getType()))
            || registry.getTypes(property.getType()).isEmpty())
            && !typeFilter.apply(qualifiedName)) {
          properties.add(property);
        }
      }
    }
    return properties;
  }

  /**
   * Extracts information on the members (both functions and properties) of the given type.
   *
   * <p>The returned report will include information on all properties on the type, regardless of
   * whether the property is defined directly on the nominal type or one of its super
   * types/interfaces.
   */
  public Report inspectInstanceType() {
    if (!inspectedType.getType().isConstructor() && !inspectedType.getType().isInterface()) {
      return new Report();
    }

    Report report = new Report();
    Multimap<String, InstanceProperty> properties = MultimapBuilder
        .treeKeys()
        .linkedHashSetValues()
        .build();

    for (JSType assignableType : getAssignableTypes(inspectedType.getType())) {
      for (Map.Entry<String, InstanceProperty> entry
          : getInstanceProperties(assignableType).entrySet()) {
        properties.put(entry.getKey(), entry.getValue());
      }
    }

    final JSType currentType = ((FunctionType) inspectedType.getType()).getInstanceType();

    for (String key : properties.keySet()) {
      Deque<InstanceProperty> definitions = new ArrayDeque<>(properties.get(key));
      JSType propertyType = findPropertyType(definitions);
      InstanceProperty property = definitions.removeFirst();

      if (property.getJsDoc() != null
          && property.getJsDoc().getVisibility() == JSDocInfo.Visibility.PRIVATE) {
        continue;
      }

      NominalType ownerType = property.getOwnerType().or(inspectedType);
      Comment definedBy = getDefinedByComment(linkFactory, ownerType, currentType, property);

      if (propertyType.isFunctionType()) {
        report.addFunction(getFunctionData(
            property.getName(),
            propertyType,
            property.getNode(),
            PropertyDocs.create(ownerType, property.getJsDoc()),
            definedBy,
            definitions));
      } else {
        report.addProperty(getPropertyData(
            property.getName(),
            propertyType,
            property.getNode(),
            PropertyDocs.create(ownerType, property.getJsDoc()),
            definedBy,
            definitions));
      }
    }

    return report;
  }

  private JSType findPropertyType(Iterable<InstanceProperty> definitions) {
    for (InstanceProperty def : definitions) {
      if (!def.getType().isUnknownType()) {
        return def.getType();
      }
    }
    return definitions.iterator().next().getType();
  }

  private Set<JSType> getAssignableTypes(JSType type) {
    if (type.isConstructor() || type.isInterface()) {
      type = ((FunctionType) type).getInstanceType();
    }
    Set<JSType> types = new LinkedHashSet<>();
    types.add(type);
    for (JSType iface : registry.getDeclaredInterfaces(type, jsRegistry)) {
      types.addAll(getAssignableTypes(iface));
    }
    List<JSType> typeHierarchy = registry.getTypeHierarchy(type, jsRegistry);
    for (int i = 1; i < typeHierarchy.size(); i++) {
      types.addAll(getAssignableTypes(typeHierarchy.get(i)));
    }
    return types;
  }

  @VisibleForTesting
  Map<String, InstanceProperty> getInstanceProperties(JSType type) {
    Map<String, InstanceProperty> properties = new HashMap<>();

    if (type.isConstructor() || type.isInterface()) {
      type = ((FunctionType) type).getInstanceType();
    }

    ObjectType object = type.toObjectType();
    FunctionType ctor = object.getConstructor();
    if (ctor != null) {
      ObjectType prototype = ObjectType.cast(ctor.getPropertyType("prototype"));
      verify(prototype != null);
      properties = getOwnProperties(prototype);
    }
    properties.putAll(getOwnProperties(object));
    return properties;
  }

  private Map<String, InstanceProperty> getOwnProperties(ObjectType object) {
    ObjectType definingType = object;
    if (definingType.isFunctionPrototypeType()) {
      definingType = definingType.getOwnerFunction();
    } else if (definingType.isInstanceType()) {
      definingType = definingType.getConstructor();
    }

    Map<String, InstanceProperty> properties = new HashMap<>();
    for (String name : object.getOwnPropertyNames()) {
      if (!"constructor".equals(name)) {
        Property property = object.getOwnSlot(name);
        properties.put(property.getName(), InstanceProperty.builder()
            .setOwnerType(getFirst(registry.getTypes(definingType), null))
            .setDefinedByType(definingType)
            .setName(property.getName())
            .setType(getType(object, property))
            .setNode(property.getNode())
            .setJsDoc(JsDoc.from(property.getJSDocInfo()))
            .build());
      }
    }
    return properties;
  }

  @Nullable
  private Comment getDefinedByComment(
      final LinkFactory linkFactory,
      final NominalType context,
      JSType currentType,
      InstanceProperty property) {
    JSType propertyDefinedOn = property.getDefinedByType();
    if (propertyDefinedOn.isInterface()
        || (propertyDefinedOn.toObjectType() != null
            && propertyDefinedOn.toObjectType().getConstructor() != null
            && propertyDefinedOn.toObjectType().getConstructor().isInterface())) {
      return null;
    }
    if (propertyDefinedOn.isConstructor()) {
      propertyDefinedOn = ((FunctionType) propertyDefinedOn).getInstanceType();
    }
    if (currentType.equals(propertyDefinedOn)) {
      return null;
    }

    final JSType definedByType = stripTemplateTypeInformation(propertyDefinedOn);

    List<NominalType> types = registry.getTypes(definedByType);
    if (types.isEmpty() && definedByType.isInstanceType()) {
      types = registry.getTypes(definedByType.toObjectType().getConstructor());
    }

    if (!types.isEmpty()) {
      TypeLink link = linkFactory.createLink(types.get(0), "#" + property.getName());
      Comment.Builder comment = Comment.newBuilder();
      comment.addTokenBuilder()
          .setText(stripHash(link.getText()))
          .setHref(link.getHref());
      return comment.build();
    }

    TypeExpressionParser parser = expressionParserFactory.create(
        linkFactory.withTypeContext(context));
    return parser.parse(definedByType);
  }

  /**
   * Finds the properties defined on a class.
   */
  @Nullable
  @CheckReturnValue
  private InstanceProperty findFirstClassOverride(Iterable<InstanceProperty> properties) {
    for (InstanceProperty property : properties) {
      JSType definedOn = property.getDefinedByType();
      if (definedOn.isInterface()) {
        continue;
      } else if (definedOn.isInstanceType()) {
        FunctionType ctor = definedOn.toObjectType().getConstructor();
        if (ctor != null && ctor.isInterface()) {
          continue;
        }
      }
      return property;
    }
    return null;
  }

  /**
   * Given a list of properties, finds those that are specified on an interface.
   */
  private Iterable<InstanceProperty> findSpecifications(Iterable<InstanceProperty> properties) {
    return FluentIterable.from(properties).filter(new Predicate<InstanceProperty>() {
      @Override
      public boolean apply(InstanceProperty property) {
        JSType definedOn = property.getDefinedByType();
        if (!definedOn.isInterface()) {
          JSType ctor = null;
          if (definedOn.isInstanceType()) {
            ctor = definedOn.toObjectType().getConstructor();
          }
          if (ctor == null || !ctor.isInterface()) {
            return false;
          }
        }
        return true;
      }
    });
  }

  public Function getFunctionData(
      String name,
      JSType type,
      Node node,
      NominalType context,
      JsDoc jsDoc) {
    PropertyDocs propertyDocs = PropertyDocs.create(context, jsDoc);
    return getFunctionData(name, type, node, propertyDocs, null,
        ImmutableList.<InstanceProperty>of());
  }

  private Function getFunctionData(
      String name,
      JSType type,
      Node node,
      PropertyDocs docs) {
    return getFunctionData(name, type, node, docs, null,
        ImmutableList.<InstanceProperty>of());
  }

  private Function getFunctionData(
      String name,
      JSType type,
      Node node,
      PropertyDocs docs,
      @Nullable Comment definedBy,
      Iterable<InstanceProperty> overrides) {
    checkArgument(type.isFunctionType(), "%s is not a function type: %s", name, type);

    boolean isConstructor = type.isConstructor() && !isFunctionTypeConstructor(type);
    boolean isInterface = !isConstructor && type.isInterface();

    Function.Builder builder = Function.newBuilder()
        .setBase(getBasePropertyDetails(name, type, node, docs, definedBy, overrides));

    if (isConstructor) {
      builder.setIsConstructor(true);
    }

    if (!isConstructor && !isInterface) {
      PropertyDocs returnDocs = findPropertyDocs(docs, overrides, new Predicate<JsDoc>() {
        @Override
        public boolean apply(JsDoc input) {
          return input.hasAnnotation(Annotation.RETURN);
        }
      });

      if (returnDocs != null) {
        Comment returnComment = parser.parseComment(
            returnDocs.getJsDoc().getReturnClause().getDescription(),
            linkFactory.withTypeContext(returnDocs.getContextType()));
        if (returnComment.getTokenCount() > 0) {
          builder.getReturnBuilder().setDescription(returnComment);
        }
      }

      Comment returnType = getReturnType(docs, overrides, (FunctionType) type);
      if (returnType.getTokenCount() > 0) {
        builder.getReturnBuilder().setType(returnType);
      }
    }

    builder.addAllTemplateName(docs.getJsDoc().getTemplateTypeNames())
        .addAllThrown(buildThrowsData(docs.getContextType(), docs.getJsDoc()))
        .addAllParameter(getParameters(type, node, docs, overrides));

    return builder.build();
  }

  private Iterable<Function.Detail> getParameters(
      JSType type,
      Node node,
      PropertyDocs docs,
      Iterable<InstanceProperty> overrides) {
    checkArgument(type.isFunctionType());

    PropertyDocs foundDocs = findPropertyDocs(docs, overrides, new Predicate<JsDoc>() {
      @Override
      public boolean apply(JsDoc input) {
        return !input.getParameters().isEmpty();
      }
    });

    // Even though we found docs with @param annotations, they may have been
    // meaningless docs with a type, no name, and no description:
    //   \**
    //    * @param {number}
    //    * @param {number}
    //    *\
    //   Clazz.prototype.add = function(x, y) { return x + y; };
    if (foundDocs != null
        && !foundDocs.getJsDoc().getParameters().isEmpty()) {
      final NominalType contextType = foundDocs.getContextType();
      return FluentIterable.from(foundDocs.getJsDoc().getParameters())
          .transform(new com.google.common.base.Function<Parameter, Detail>() {
            @Override
            public Detail apply(Parameter input) {
              Detail.Builder detail = Detail.newBuilder().setName(input.getName());
              if (!isNullOrEmpty(input.getDescription())) {
                detail.setDescription(
                    parser.parseComment(
                        input.getDescription(), linkFactory.withTypeContext(contextType)));
              }
              if (input.getType() != null) {
                detail.setType(
                    expressionParserFactory
                        .create(linkFactory.withTypeContext(contextType))
                        .parse(input.getType()));
              }
              return detail.build();
            }
          });
    }

    if (isFunctionTypeConstructor(type)) {
      return ImmutableList.of();
    }

    List<Node> parameterNodes = Lists.newArrayList(((FunctionType) type).getParameters());
    List<Detail> details = new ArrayList<>(parameterNodes.size());
    @Nullable Node paramList = findParamList(node);

    LinkFactory factory = linkFactory.withTypeContext(
        foundDocs == null ? docs.getContextType() : foundDocs.getContextType());
    TypeExpressionParser parser = expressionParserFactory.create(factory);
    for (int i = 0; i < parameterNodes.size(); i++) {
      Detail.Builder detail = Detail.newBuilder().setName("arg" + i);
      Node parameterNode = parameterNodes.get(i);
      if (!parameterNode.getJSType().isNoType() && !parameterNode.getJSType().isNoResolvedType()) {
        detail.setType(parser.parse(parameterNode));
      }
      if (paramList != null && i < paramList.getChildCount()) {
        String name = paramList.getChildAtIndex(i).getString();
        detail.setName(name);
      }
      details.add(detail.build());
    }
    return details;
  }

  /**
   * Returns whether the given {@code type} looks like the base function type constructor (that is,
   * {@code @type {!Function}}).
   */
  private static boolean isFunctionTypeConstructor(JSType type) {
    return type.isConstructor()
        && ((FunctionType) type).getInstanceType().isUnknownType();
  }

  @Nullable
  private static Node findParamList(Node src) {
    if (src.isName() && src.getParent().isFunction()) {
      verify(src.getNext().isParamList());
      return src.getNext();
    }

    if (src.isGetProp()
        && src.getParent().isAssign()
        && src.getParent().getFirstChild() != null
        && src.getParent().getFirstChild().getNext().isFunction()) {
      src = src.getParent().getFirstChild().getNext();
      return src.getFirstChild().getNext();
    }

    if (!src.isFunction()
        && src.getFirstChild() != null
        && src.getFirstChild().isFunction()) {
      src = src.getFirstChild();
    }

    if (src.isFunction()) {
      Node node = src.getFirstChild().getNext();
      verify(node.isParamList());
      return node;
    }

    return null;
  }

  private Iterable<Function.Detail> buildThrowsData(final NominalType context, JsDoc jsDoc) {
    return transform(jsDoc.getThrowsClauses(),
        new com.google.common.base.Function<TypedDescription, Detail>() {
          @Override
          public Function.Detail apply(TypedDescription input) {
            Comment thrownType = Comment.getDefaultInstance();
            if (input.getType().isPresent()) {
              thrownType = expressionParserFactory
                  .create(linkFactory.withTypeContext(context))
                  .parse(input.getType().get());
            }
            return Function.Detail.newBuilder()
                .setType(thrownType)
                .setDescription(parser.parseComment(
                    input.getDescription(),
                    linkFactory.withTypeContext(context)))
                .build();
          }
        }
    );
  }

  private Comment getReturnType(
      PropertyDocs docs,
      Iterable<InstanceProperty> overrides,
      FunctionType function) {
    PropertyDocs returnDocs = findPropertyDocs(docs, overrides, new Predicate<JsDoc>() {
      @Override
      public boolean apply(@Nullable JsDoc input) {
        return input != null && input.getReturnClause().getType().isPresent();
      }
    });

    JSType returnType = function.getReturnType();
    if (returnType.isUnknownType() && returnDocs != null) {
      returnType =
          returnDocs.getJsDoc().getReturnClause().getType().get().evaluate(null, jsRegistry);
    }

    if (returnType.isUnknownType()) {
      for (InstanceProperty property : overrides) {
        if (property.getType() != null && property.getType().isFunctionType()) {
          FunctionType fn = (FunctionType) property.getType();
          if (fn.getReturnType() != null && !fn.getReturnType().isUnknownType()) {
            returnType = fn.getReturnType();
            break;
          }
        }
      }
    }

    NominalType context = null;
    if (returnDocs != null) {
      context = returnDocs.getContextType();
    } else if (docs != null) {
      context = docs.getContextType();
    }

    TypeExpressionParser parser = expressionParserFactory.create(
        linkFactory.withTypeContext(context));
    Comment comment = parser.parse(returnType);
    if (isVacuousTypeComment(comment)) {
      return Comment.getDefaultInstance();
    }
    return comment;
  }

  private com.github.jsdossier.proto.Property getPropertyData(
      String name,
      JSType type,
      Node node,
      PropertyDocs docs) {
    return getPropertyData(name, type, node, docs, null, ImmutableList.<InstanceProperty>of());
  }

  private com.github.jsdossier.proto.Property getPropertyData(
      String name,
      JSType type,
      Node node,
      PropertyDocs docs,
      @Nullable Comment definedBy,
      Iterable<InstanceProperty> overrides) {
    com.github.jsdossier.proto.Property.Builder builder =
        com.github.jsdossier.proto.Property.newBuilder()
            .setBase(getBasePropertyDetails(name, type, node, docs, definedBy, overrides));

    TypeExpressionParser parser = expressionParserFactory
        .create(linkFactory.withTypeContext(docs.getContextType()));
    if (docs.getJsDoc().getType() != null) {
      builder.setType(parser.parse(docs.getJsDoc().getType()));
    } else if (type != null) {
      builder.setType(parser.parse(type));
    }

    return builder.build();
  }

  private BaseProperty getBasePropertyDetails(
      String name,
      JSType type,
      Node node,
      PropertyDocs docs,
      @Nullable Comment definedBy,
      Iterable<InstanceProperty> overrides) {
    BaseProperty.Builder builder = BaseProperty.newBuilder()
        .setName(name)
        .setDescription(findBlockComment(linkFactory, docs, overrides))
        .setSource(linkFactory.withTypeContext(docs.getContextType()).createLink(node));

    if (registry.isModule(type)) {
      builder.getTagsBuilder().setIsModule(true);
    }

    if ("default".equals(name)
        && docs.getContextType() == inspectedType
        && inspectedType.isModuleExports()
        && inspectedType.getModule().get().getType() == Module.Type.ES6) {
      builder.getTagsBuilder().setIsDefault(true);
    }

    if (definedBy != null) {
      builder.setDefinedBy(definedBy);
    }

    InstanceProperty immediateOverride = findFirstClassOverride(overrides);
    if (immediateOverride != null) {
      builder.setOverrides(getPropertyLink(docs.getContextType(), immediateOverride));
    }

    for (InstanceProperty property : findSpecifications(overrides)) {
      builder.addSpecifiedBy(getPropertyLink(docs.getContextType(), property));
    }

    JSDocInfo.Visibility visibility = determineVisibility(docs, overrides);
    if (visibility != JSDocInfo.Visibility.PUBLIC) {
      builder.setVisibility(Visibility.valueOf(visibility.name()));
    }

    LinkFactory contextLinkFactory = linkFactory.withTypeContext(docs.getContextType());

    JsDoc jsdoc = docs.getJsDoc();
    if (jsdoc.isDeprecated()) {
      builder.getTagsBuilder().setIsDeprecated(true);
      builder.setDeprecation(
          parser.parseComment(
              jsdoc.getDeprecationReason(),
              contextLinkFactory));
    }

    for (String seeAlso : jsdoc.getSeeClauses()) {
      // 1) Try as a link reference to another type.
      @Nullable TypeLink link = contextLinkFactory.createLink(seeAlso);
      if (link != null && !link.getHref().isEmpty()) {
        builder.addSeeAlsoBuilder()
            .addTokenBuilder()
            .setText(seeAlso)
            .setHref(link.getHref());
        continue;
      }

      if (URI_PATTERN.matcher(seeAlso).matches()) {
        seeAlso = "<" + seeAlso + ">";
      }

      builder.addSeeAlso(parser.parseComment(seeAlso, contextLinkFactory));
    }

    if (!type.isFunctionType() && (jsdoc.isConst() || jsdoc.isDefine())) {
      builder.getTagsBuilder().setIsConst(true);
    }
    return builder.build();
  }

  private JSDocInfo.Visibility determineVisibility(
      PropertyDocs docs, Iterable<InstanceProperty> overrides) {
    JsDoc jsdoc = docs.getJsDoc();
    JSDocInfo.Visibility vis = jsdoc.getVisibility();
    if (vis == JSDocInfo.Visibility.INHERITED) {
      for (InstanceProperty superType : overrides) {
        vis = getVisibility(superType);
        if (vis != JSDocInfo.Visibility.INHERITED) {
          break;
        }
      }
    }
    if (vis == JSDocInfo.Visibility.INHERITED) {
      vis = registry.getDefaultVisibility(docs.getContextType().getSourceFile());
    }
    return vis;
  }

  private JSDocInfo.Visibility getVisibility(InstanceProperty property) {
    JsDoc docs = property.getJsDoc();
    if (docs == null) {
      return JSDocInfo.Visibility.INHERITED;
    }
    return docs.getVisibility();
  }

  private Comment findBlockComment(
      LinkFactory linkFactory,
      PropertyDocs docs,
      Iterable<InstanceProperty> overrides) {
    docs = findPropertyDocs(docs, overrides, new Predicate<JsDoc>() {
      @Override
      public boolean apply(JsDoc input) {
        return !isNullOrEmpty(input.getBlockComment());
      }
    });
    return docs == null
        ? Comment.getDefaultInstance()
        : parser.parseComment(
            docs.getJsDoc().getBlockComment(),
            linkFactory.withTypeContext(docs.getContextType()));
  }

  @Nullable
  private PropertyDocs findPropertyDocs(
      PropertyDocs docs,
      Iterable<InstanceProperty> overrides,
      Predicate<JsDoc> predicate) {
    if (predicate.apply(docs.getJsDoc())) {
      return docs;
    }
    for (InstanceProperty property : overrides) {
      if (predicate.apply(property.getJsDoc())) {
        List<NominalType> types = registry.getTypes(property.getType());
        if (types.isEmpty()) {
          return PropertyDocs.create(docs.getContextType(), property.getJsDoc());
        }
        return PropertyDocs.create(types.get(0), property.getJsDoc());
      }
    }
    return null;
  }

  private Comment getPropertyLink(NominalType context, InstanceProperty property) {
    JSType type = property.getDefinedByType();

    if (property.getOwnerType().isPresent()) {
      TypeLink link =
          linkFactory.createLink(property.getOwnerType().get(), "#" + property.getName());
      return Comment.newBuilder()
          .addToken(Comment.Token.newBuilder()
              .setText(stripHash(link.getText()))
              .setHref(link.getHref()))
          .build();
    }

    if (type.isConstructor() || type.isInterface()) {
      type = ((FunctionType) type).getInstanceType();
    }
    type = stripTemplateTypeInformation(type);
    return expressionParserFactory
        .create(linkFactory.withTypeContext(context))
        .parse(type);
  }

  static Node fakeNodeForType(final NominalType type) {
    Node fakeNode = IR.script();
    fakeNode.getSourceFileName();
    fakeNode.setStaticSourceFile(
        new StaticSourceFile() {
          @Override public String getName() { return type.getSourceFile().toString(); }
          @Override public boolean isExtern() { return false; }
          @Override public int getLineOffset(int lineNumber) { return 0; }
          @Override public int getLineOfOffset(int offset) { return 0; }
          @Override public int getColumnOfOffset(int offset) { return 0; }
        }
    );
    fakeNode.setLineno(type.getSourcePosition().getLine());
    return fakeNode;
  }

  private static String stripHash(String text) {
    int index = text.indexOf('#');
    if (index != -1) {
      text = text.substring(0, index);
    }
    return text;
  }

  private static JSType stripTemplateTypeInformation(JSType type) {
    if (type.isTemplatizedType()) {
      return ((TemplatizedType) type).getReferencedType();
    }
    return type;
  }

  private static JSType getType(ObjectType object, Property property) {
    JSType type = object.findPropertyType(property.getName());
    if (type.isUnknownType()) {
      type = property.getType();
    }
    return type;
  }

  public static final class Report {
    private List<com.github.jsdossier.proto.Function> functions = new ArrayList<>();
    private List<com.github.jsdossier.proto.Property> properties = new ArrayList<>();
    private List<com.github.jsdossier.proto.Property> compilerConstants = new ArrayList<>();

    private void addFunction(com.github.jsdossier.proto.Function function) {
      functions.add(function);
    }

    private void addProperty(com.github.jsdossier.proto.Property property) {
      properties.add(property);
    }

    private void addCompilerConstant(com.github.jsdossier.proto.Property property) {
      compilerConstants.add(property);
    }

    public List<com.github.jsdossier.proto.Function> getFunctions() {
      return functions;
    }

    public List<com.github.jsdossier.proto.Property> getProperties() {
      return properties;
    }

    public List<com.github.jsdossier.proto.Property> getCompilerConstants() {
      return compilerConstants;
    }
  }

  @AutoValue
  static abstract class PropertyDocs {
    static PropertyDocs create(NominalType context, JsDoc jsDoc) {
      return new AutoValue_TypeInspector_PropertyDocs(context, jsDoc);
    }

    abstract NominalType getContextType();
    abstract JsDoc getJsDoc();
  }

  @AutoValue
  static abstract class InstanceProperty {
    static Builder builder() {
      return new AutoValue_TypeInspector_InstanceProperty.Builder();
    }

    abstract Optional<NominalType> getOwnerType();
    abstract JSType getDefinedByType();
    abstract String getName();
    abstract JSType getType();
    abstract Node getNode();
    abstract JsDoc getJsDoc();

    @AutoValue.Builder
    static abstract class Builder {
      final Builder setOwnerType(@Nullable NominalType type) {
        return setOwnerType(Optional.fromNullable(type));
      }

      abstract Builder setOwnerType(Optional<NominalType> type);
      abstract Builder setDefinedByType(JSType type);
      abstract Builder setName(String name);
      abstract Builder setType(JSType type);
      abstract Builder setNode(Node node);
      abstract Builder setJsDoc(JsDoc doc);
      abstract InstanceProperty build();
    }
  }
}
