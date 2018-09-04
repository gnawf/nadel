package graphql.nadel;

import com.atlassian.braid.Braid;
import com.atlassian.braid.Link;
import com.atlassian.braid.SchemaNamespace;
import com.atlassian.braid.SchemaSource;
import com.atlassian.braid.TypeUtils;
import com.atlassian.braid.document.TypeMapper;
import com.atlassian.braid.document.TypeMappers;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.PublicApi;
import graphql.execution.AsyncExecutionStrategy;
import graphql.language.Definition;
import graphql.language.FieldDefinition;
import graphql.language.SDLDefinition;
import graphql.nadel.TransformationUtils.FieldDefinitionWithParentType;
import graphql.nadel.dsl.FieldTransformation;
import graphql.nadel.dsl.InnerServiceHydration;
import graphql.nadel.dsl.ServiceDefinition;
import graphql.nadel.dsl.StitchingDsl;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeInfo;
import graphql.schema.idl.errors.SchemaProblem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static graphql.Assert.assertNotNull;
import static graphql.nadel.TransformationUtils.collectFieldTransformations;

@PublicApi
public class Nadel {
    private StitchingDsl stitchingDsl;
    private Parser parser = new Parser();
    private Braid braid;

    private final Map<String, TypeDefinitionRegistry> typesByService = new LinkedHashMap<>();
    private final Map<String, SchemaNamespace> namespaceByService = new LinkedHashMap<>();

    public Nadel(String dsl, GraphQLRemoteRetrieverFactory<?> graphQLRemoteRetrieverFactory) {
        this(dsl, new GraphQLRemoteSchemaSourceFactory<>(graphQLRemoteRetrieverFactory));
    }

    public Nadel(String dsl, SchemaSourceFactory schemaSourceFactory) {
        this.stitchingDsl = this.parser.parseDSL(dsl);

        List<ServiceDefinition> serviceDefinitions = stitchingDsl.getServiceDefinitions();

        for (ServiceDefinition serviceDefinition : serviceDefinitions) {
            TypeDefinitionRegistry typeDefinitionRegistry = buildRegistry(serviceDefinition);
            this.typesByService.put(serviceDefinition.getName(), typeDefinitionRegistry);
            SchemaNamespace schemaNamespace = SchemaNamespace.of(serviceDefinition.getName());
            namespaceByService.put(serviceDefinition.getName(), schemaNamespace);

        }
        List<SchemaSource> schemaSources = new ArrayList<>();
        for (ServiceDefinition serviceDefinition : serviceDefinitions) {
            SchemaNamespace namespace = namespaceByService.get(serviceDefinition.getName());
            TypeDefinitionRegistry typeDefinitionRegistry = typesByService.get(serviceDefinition.getName());

            final List<FieldDefinitionWithParentType> defs = collectFieldTransformations(serviceDefinition);

            List<Link> links = createLinks(namespace, defs);
            List<TypeMapper> mappers = createMappers(defs);
            SchemaSource schemaSource = schemaSourceFactory.createSchemaSource(serviceDefinition, namespace,
                    typeDefinitionRegistry, links, mappers);
            schemaSources.add(schemaSource);
        }

        AsyncExecutionStrategy asyncExecutionStrategy = new AsyncExecutionStrategy();
        this.braid = Braid.builder()
                .executionStrategy(asyncExecutionStrategy)
                .schemaSources(schemaSources)
                .build();
    }

    public TypeDefinitionRegistry buildRegistry(ServiceDefinition serviceDefinition) {
        List<GraphQLError> errors = new ArrayList<>();
        TypeDefinitionRegistry typeRegistry = new TypeDefinitionRegistry();
        for (Definition definition : serviceDefinition.getTypeDefinitions()) {
            if (definition instanceof SDLDefinition) {
                typeRegistry.add((SDLDefinition) definition).ifPresent(errors::add);
            }
        }
        if (errors.size() > 0) {
            throw new SchemaProblem(errors);
        } else {
            return typeRegistry;
        }
    }

    private List<Link> createLinks(SchemaNamespace schemaNamespace, List<FieldDefinitionWithParentType> defs) {
        List<Link> links = new ArrayList<>();
        for (FieldDefinitionWithParentType definition : defs) {
            final FieldTransformation transformation = definition.field().getFieldTransformation();
            if (transformation.getInnerServiceHydration() != null) {
                final InnerServiceHydration hydration = transformation.getInnerServiceHydration();
                final Link link = createHydrationLink(schemaNamespace, definition, hydration);
                links.add(link);
            }
        }
        return links;
    }

    private List<TypeMapper> createMappers(List<FieldDefinitionWithParentType> defs) {
        Map<String, TypeMapper> typeMapperMap = new HashMap<>();
        for (FieldDefinitionWithParentType definition : defs) {
            final FieldTransformation transformation = definition.field().getFieldTransformation();
            if (transformation.getFieldMappingDefinition() != null) {
                typeMapperMap.compute(definition.parentType(), (k, v) ->
                        ((v == null) ? TypeMappers.typeNamed(definition.parentType()) : v)
                                .copy(definition.field().getName(),
                                        transformation.getFieldMappingDefinition().getInputName()));
            }
        }
        return typeMapperMap.values().stream().map(TypeMapper::copyRemaining).collect(Collectors.toList());
    }


    private Link createHydrationLink(SchemaNamespace schemaNamespace, FieldDefinitionWithParentType definition,
                                     InnerServiceHydration hydration) {
        SchemaNamespace targetService = assertNotNull(this.namespaceByService.get(hydration.getServiceName()));
        final FieldDefinition targetField = findTargetFieldForHydration(hydration);
        //TODO: will not work for lists or non nullable types. does braid support this at all?
        String targetTypeName = TypeInfo.typeInfo(targetField.getType()).getName();

        //TODO: braid does not support multiple arguments, so we just take first
        final String fromField = hydration.getArguments().entrySet().stream()
                .map(e -> e.getValue().getInputName())
                .findFirst()
                .orElse(definition.field().getName());

        return Link.from(schemaNamespace, definition.parentType(), definition.field().getName(), fromField)
                //TODO: we need to add something to DSL to support 'queryVariableArgument' parameter of .to
                // by default it is targetField name which is not always correct.
                .to(targetService, targetTypeName, targetField.getName())
                .replaceFromField()
                .build();
    }


    private FieldDefinition findTargetFieldForHydration(InnerServiceHydration hydration) {
        final TypeDefinitionRegistry types = typesByService.get(hydration.getServiceName());
        if (types == null) {
            throw hydrationError(hydration, "Service '%s' is not defined.", hydration.getServiceName());
        }

        return TypeUtils.findQueryFieldDefinitions(types)
                .flatMap(queryFields -> queryFields.stream()
                        .filter(field -> hydration.getTopLevelField().equals(field.getName()))
                        .findFirst())
                .orElseThrow(() -> hydrationError(hydration, "Service '%s' does not contain query field '%s'",
                        hydration.getServiceName(), hydration.getTopLevelField()));
    }

    private InvalidDslException hydrationError(InnerServiceHydration hydration, String format, Object... args) {
        return new InvalidDslException(String.format("Error in field hydration definition: " + format, args),
                hydration.getSourceLocation());
    }


    public CompletableFuture<ExecutionResult> executeAsync(ExecutionInput executionInput) {
        return this.braid.newGraphQL().execute(executionInput);
    }

}
