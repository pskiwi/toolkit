/* Copyright 2017 Google Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.transformer;

import com.google.api.codegen.ServiceMessages;
import com.google.api.codegen.config.FieldConfig;
import com.google.api.codegen.config.GapicMethodConfig;
import com.google.api.codegen.config.GrpcStreamingConfig.GrpcStreamingType;
import com.google.api.codegen.metacode.InitCodeContext;
import com.google.api.codegen.metacode.InitCodeContext.InitCodeOutputType;
import com.google.api.codegen.util.Name;
import com.google.api.codegen.viewmodel.ApiMethodDocView;
import com.google.api.codegen.viewmodel.ClientMethodType;
import com.google.api.codegen.viewmodel.InitCodeView;
import com.google.api.codegen.viewmodel.OptionalArrayMethodView;
import com.google.api.codegen.viewmodel.RequestObjectParamView;
import com.google.api.tools.framework.model.Field;
import com.google.api.tools.framework.model.Method;
import com.google.api.tools.framework.model.Oneof;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;

/**
 * DynamicLangApiMethodTransformer generates view objects from method definitions for dynamic
 * languages.
 */
public class DynamicLangApiMethodTransformer {
  private final ApiMethodParamTransformer apiMethodParamTransformer;
  private final InitCodeTransformer initCodeTransformer;
  private final LongRunningTransformer lroTransformer = new LongRunningTransformer();

  public DynamicLangApiMethodTransformer(ApiMethodParamTransformer apiMethodParamTransformer) {
    this(apiMethodParamTransformer, new InitCodeTransformer());
  }

  public DynamicLangApiMethodTransformer(
      ApiMethodParamTransformer apiMethodParamTransformer,
      InitCodeTransformer initCodeTransformer) {
    this.apiMethodParamTransformer = apiMethodParamTransformer;
    this.initCodeTransformer = initCodeTransformer;
  }

  public OptionalArrayMethodView generateMethod(GapicMethodContext context) {
    return generateMethod(context, false);
  }

  public OptionalArrayMethodView generateMethod(
      GapicMethodContext context, boolean packageHasMultipleServices) {
    SurfaceNamer namer = context.getNamer();
    OptionalArrayMethodView.Builder apiMethod = OptionalArrayMethodView.newBuilder();

    if (context.getMethodConfig().isPageStreaming()) {
      apiMethod.type(ClientMethodType.PagedOptionalArrayMethod);
    } else {
      apiMethod.type(ClientMethodType.OptionalArrayMethod);
    }
    apiMethod.apiClassName(namer.getApiWrapperClassName(context.getInterfaceConfig()));
    apiMethod.fullyQualifiedApiClassName(
        namer.getFullyQualifiedApiWrapperClassName(context.getInterfaceConfig()));
    apiMethod.topLevelAliasedApiClassName(
        namer.getTopLevelAliasedApiClassName(
            context.getInterfaceConfig(), packageHasMultipleServices));
    apiMethod.versionAliasedApiClassName(
        namer.getVersionAliasedApiClassName(
            context.getInterfaceConfig(), packageHasMultipleServices));
    apiMethod.apiVariableName(namer.getApiWrapperVariableName(context.getInterfaceConfig()));
    apiMethod.apiModuleName(namer.getApiWrapperModuleName());
    InitCodeOutputType initCodeOutputType =
        context.getMethod().getRequestStreaming()
            ? InitCodeOutputType.SingleObject
            : InitCodeOutputType.FieldList;
    InitCodeView initCode =
        initCodeTransformer.generateInitCode(
            context.cloneWithEmptyTypeTable(),
            createInitCodeContext(
                context, context.getMethodConfig().getRequiredFieldConfigs(), initCodeOutputType));
    apiMethod.initCode(initCode);

    apiMethod.doc(generateMethodDoc(context));

    apiMethod.name(
        namer.getApiMethodName(context.getMethod(), context.getMethodConfig().getVisibility()));
    apiMethod.requestVariableName(namer.getRequestVariableName(context.getMethod()));
    apiMethod.requestTypeName(
        namer.getRequestTypeName(context.getTypeTable(), context.getMethod().getInputType()));
    apiMethod.hasReturnValue(!ServiceMessages.s_isEmptyType(context.getMethod().getOutputType()));
    apiMethod.key(namer.getMethodKey(context.getMethod()));
    apiMethod.grpcMethodName(namer.getGrpcMethodName(context.getMethod()));
    apiMethod.stubName(namer.getStubName(context.getTargetInterface()));

    apiMethod.methodParams(apiMethodParamTransformer.generateMethodParams(context));

    Iterable<FieldConfig> filteredFieldConfigs =
        removePageTokenFieldConfig(context, context.getMethodConfig().getOptionalFieldConfigs());
    List<RequestObjectParamView> requiredParams =
        generateRequestObjectParams(context, context.getMethodConfig().getRequiredFieldConfigs());
    List<RequestObjectParamView> optionalParams =
        generateRequestObjectParams(context, context.getMethodConfig().getOptionalFieldConfigs());
    List<RequestObjectParamView> optionalParamsNoPageToken =
        generateRequestObjectParams(context, filteredFieldConfigs);
    apiMethod.requiredRequestObjectParams(requiredParams);
    apiMethod.optionalRequestObjectParams(optionalParams);
    apiMethod.optionalRequestObjectParamsNoPageToken(optionalParamsNoPageToken);
    apiMethod.hasRequestParameters(
        !requiredParams.isEmpty() || !optionalParamsNoPageToken.isEmpty());
    apiMethod.hasRequiredParameters(!requiredParams.isEmpty());

    GrpcStreamingType grpcStreamingType = context.getMethodConfig().getGrpcStreamingType();
    apiMethod.grpcStreamingType(grpcStreamingType);
    apiMethod.isSingularRequestMethod(
        grpcStreamingType.equals(GrpcStreamingType.NonStreaming)
            || grpcStreamingType.equals(GrpcStreamingType.ServerStreaming));

    apiMethod.packageName(namer.getPackageName());
    apiMethod.packageHasMultipleServices(packageHasMultipleServices);
    apiMethod.packageServiceName(namer.getPackageServiceName(context.getInterface()));
    apiMethod.apiVersion(namer.getApiWrapperModuleVersion());
    apiMethod.longRunningView(
        context.getMethodConfig().isLongRunningOperation()
            ? lroTransformer.generateDetailView(context)
            : null);

    apiMethod.oneofParams(generateOneOfParams(context.getMethodConfig().getOneofs(), namer));

    return apiMethod.build();
  }

  private ApiMethodDocView generateMethodDoc(GapicMethodContext context) {
    ApiMethodDocView.Builder docBuilder = ApiMethodDocView.newBuilder();
    SurfaceNamer surfaceNamer = context.getNamer();
    Method method = context.getMethod();
    GapicMethodConfig methodConfig = context.getMethodConfig();

    docBuilder.mainDocLines(surfaceNamer.getDocLines(method, methodConfig));
    docBuilder.paramDocs(apiMethodParamTransformer.generateParamDocs(context));
    docBuilder.returnTypeName(surfaceNamer.getDynamicLangReturnTypeName(method, methodConfig));
    docBuilder.returnsDocLines(
        surfaceNamer.getReturnDocLines(
            context.getSurfaceTransformerContext(), methodConfig, Synchronicity.Sync));
    if (methodConfig.isPageStreaming()) {
      docBuilder.pageStreamingResourceTypeName(
          surfaceNamer.getTypeNameDoc(
              context.getTypeTable(),
              methodConfig.getPageStreaming().getResourcesField().getType()));
    }
    docBuilder.throwsDocLines(surfaceNamer.getThrowsDocLines(methodConfig));

    return docBuilder.build();
  }

  private List<RequestObjectParamView> generateRequestObjectParams(
      GapicMethodContext context, Iterable<FieldConfig> fieldConfigs) {
    List<RequestObjectParamView> params = new ArrayList<>();
    for (FieldConfig fieldConfig : fieldConfigs) {
      params.add(generateRequestObjectParam(context, fieldConfig));
    }
    return params;
  }

  private Iterable<FieldConfig> removePageTokenFieldConfig(
      GapicMethodContext context, Iterable<FieldConfig> fieldConfigs) {
    GapicMethodConfig methodConfig = context.getMethodConfig();
    if (methodConfig == null || !methodConfig.isPageStreaming()) {
      return fieldConfigs;
    }
    final Field requestTokenField = methodConfig.getPageStreaming().getRequestTokenField();
    return Iterables.filter(
        fieldConfigs,
        new Predicate<FieldConfig>() {
          @Override
          public boolean apply(FieldConfig fieldConfig) {
            return !fieldConfig.getField().equals(requestTokenField);
          }
        });
  }

  private RequestObjectParamView generateRequestObjectParam(
      GapicMethodContext context, FieldConfig fieldConfig) {
    SurfaceNamer namer = context.getNamer();
    FeatureConfig featureConfig = context.getFeatureConfig();
    ModelTypeTable typeTable = context.getTypeTable();
    Field field = fieldConfig.getField();

    Iterable<Field> requiredFields = context.getMethodConfig().getRequiredFields();
    boolean isRequired = false;
    for (Field f : requiredFields) {
      if (f.getSimpleName().equals(field.getSimpleName())) {
        isRequired = true;
      }
    }

    RequestObjectParamView.Builder param = RequestObjectParamView.newBuilder();
    param.name(namer.getVariableName(field));
    param.keyName(namer.getFieldKey(field));
    param.nameAsMethodName(namer.getFieldGetFunctionName(featureConfig, fieldConfig));
    param.typeName(typeTable.getAndSaveNicknameFor(field.getType()));
    param.elementTypeName(typeTable.getAndSaveNicknameForElementType(field.getType()));
    param.setCallName(namer.getFieldSetFunctionName(featureConfig, fieldConfig));
    param.addCallName(namer.getFieldAddFunctionName(field));
    param.getCallName(namer.getFieldGetFunctionName(featureConfig, fieldConfig));
    param.isMap(field.getType().isMap());
    param.isArray(!field.getType().isMap() && field.getType().isRepeated());
    param.isPrimitive(namer.isPrimitive(field.getType()));
    param.isOptional(!isRequired);
    if (!isRequired) {
      param.optionalDefault(namer.getOptionalFieldDefaultValue(fieldConfig, context));
    }
    return param.build();
  }

  private List<List<String>> generateOneOfParams(Iterable<Oneof> oneofs, SurfaceNamer namer) {
    ImmutableList.Builder<List<String>> oneofParams = ImmutableList.builder();
    for (Oneof oneof : oneofs) {
      ImmutableList.Builder<String> oneofFields = ImmutableList.builder();
      for (Field field : oneof.getFields()) {
        oneofFields.add(namer.getVariableName(field));
      }
      oneofParams.add(oneofFields.build());
    }
    return oneofParams.build();
  }

  private InitCodeContext createInitCodeContext(
      GapicMethodContext context,
      Iterable<FieldConfig> fieldConfigs,
      InitCodeOutputType initCodeOutputType) {
    return InitCodeContext.newBuilder()
        .initObjectType(context.getMethod().getInputType())
        .suggestedName(Name.from("request"))
        .initFieldConfigStrings(context.getMethodConfig().getSampleCodeInitFields())
        .initValueConfigMap(InitCodeTransformer.createCollectionMap(context))
        .initFields(FieldConfig.toFieldIterable(fieldConfigs))
        .outputType(initCodeOutputType)
        .fieldConfigMap(FieldConfig.toFieldConfigMap(fieldConfigs))
        .build();
  }
}
