package graphql.nadel.enginekt.blueprint

import graphql.nadel.Service
import graphql.nadel.dsl.EnumTypeDefinitionWithTransformation
import graphql.nadel.dsl.ExtendedFieldDefinition
import graphql.nadel.dsl.FieldMappingDefinition
import graphql.nadel.dsl.InputObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.InterfaceTypeDefinitionWithTransformation
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.TypeMappingDefinition
import graphql.nadel.dsl.UnderlyingServiceHydration
import graphql.nadel.enginekt.blueprint.hydration.HydrationArgument
import graphql.nadel.enginekt.util.getFieldAt
import graphql.nadel.enginekt.util.toMap
import graphql.nadel.schema.NadelDirectives
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.FieldCoordinates.coordinates as createFieldCoordinates

object GraphQLExecutionBlueprintFactory {
    fun create(overallSchema: GraphQLSchema, services: List<Service>): GraphQLExecutionBlueprint {
        val underlyingTypes = getUnderlyingTypes(overallSchema).toMap {
            it.overallName
        }
        val underlyingFields = getUnderlyingFields(overallSchema).toMap {
            createFieldCoordinates(it.parentTypeName, it.overallName)
        }

        return GraphQLExecutionBlueprint(
            underlyingFields,
            underlyingTypes,
            emptyMap(),
        )
    }

    private fun getArtificialFields(overallSchema: GraphQLSchema, services: List<Service>) {
        overallSchema.typeMap
            .asSequence()
            .filterIsInstance<GraphQLFieldsContainer>()
            .flatMap { type ->
                type.fields
                    .asSequence()
                    // Get the field mapping def
                    .mapNotNull { field ->
                        when (val mappingDefinition = getFieldDefinitionMapping(field)) {
                            null -> when (val hydration = getUnderlyingServiceHydration(field)) {
                                null -> null
                                else -> getHydrationField(services, type, field, hydration)
                            }
                            else -> when (mappingDefinition.inputPath.size) {
                                1 -> getUnderlyingField(type, field, mappingDefinition)
                                else -> null
                            }
                        }
                    }
            }
    }

    private fun getHydrationField(
        services: List<Service>,
        type: GraphQLFieldsContainer,
        field: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
    ): GraphQLArtificialFieldDefinition {
        val hydrationService = services.single { it.name == hydration.serviceName }
        val underlyingSchema = hydrationService.underlyingSchema

        val pathToSourceField = listOfNotNull(hydration.syntheticField, hydration.topLevelField)
        val sourceField = underlyingSchema.queryType.getFieldAt(pathToSourceField)!!

        if (GraphQLTypeUtil.isList(sourceField.type)) {
            return getBatchHydrationField(type, field, hydration)
        }
    }

    private fun getBatchHydrationField(
        type: GraphQLFieldsContainer,
        field: GraphQLFieldDefinition,
        hydration: UnderlyingServiceHydration,
    ): GraphQLBatchHydrationFieldDefinition {
        val location = createFieldCoordinates(type, field)

        return GraphQLBatchHydrationFieldDefinition(
            location,
            sourceService = hydration.serviceName,
            pathToSourceField = listOfNotNull(hydration.syntheticField, hydration.topLevelField),
            arguments = getHydrationArguments(hydration),
            batchSize = hydration.batchSize ?: ,
        )
    }

    private fun getHydrationArguments(hydration: UnderlyingServiceHydration): List<HydrationArgument> {
        TODO("Not yet implemented")
    }

    private fun getFieldDefinitionMapping(field: GraphQLFieldDefinition): FieldMappingDefinition? {
        val extendedDef = field.definition as? ExtendedFieldDefinition
        return extendedDef?.fieldTransformation?.fieldMappingDefinition
            ?: NadelDirectives.createFieldMapping(field)
    }

    private fun getUnderlyingServiceHydration(field: GraphQLFieldDefinition): UnderlyingServiceHydration? {
        val extendedDef = field.definition as? ExtendedFieldDefinition
        return extendedDef?.fieldTransformation?.underlyingServiceHydration
            ?: NadelDirectives.createUnderlyingServiceHydration(field)
    }

    private fun getUnderlyingFields(overallSchema: GraphQLSchema): Sequence<GraphQLUnderlyingField> {
        return overallSchema.typeMap
            .asSequence()
            .filterIsInstance<GraphQLFieldsContainer>()
            .flatMap { type ->
                type.fields
                    .asSequence()
                    // Get the field mapping def
                    .mapNotNull { field ->
                        when (val def = field.definition) {
                            is ExtendedFieldDefinition -> field to def.fieldTransformation.fieldMappingDefinition
                            else -> null
                        }
                    }
                    .mapNotNull { (field, mappingDefinition) ->
                        // Only handle basic renames
                        when (mappingDefinition.inputPath.size) {
                            1 -> getUnderlyingField(type, field, mappingDefinition)
                            else -> null
                        }
                    }
            }
    }

    private fun getUnderlyingField(
        type: GraphQLFieldsContainer,
        field: GraphQLFieldDefinition,
        mappingDefinition: FieldMappingDefinition,
    ): GraphQLUnderlyingField {
        return GraphQLUnderlyingField(
            parentTypeName = type.name,
            overallName = field.name,
            underlyingName = mappingDefinition.inputPath.single(),
        )
    }

    private fun getUnderlyingTypes(overallSchema: GraphQLSchema): Sequence<GraphQLUnderlyingType> {
        return overallSchema.typeMap.values
            .asSequence()
            .mapNotNull { type ->
                when (val def = type.definition) {
                    is ObjectTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                    is InterfaceTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                    is InputObjectTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                    is EnumTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                    else -> null
                }
            }
    }

    private fun getUnderlyingType(typeMappingDefinition: TypeMappingDefinition): GraphQLUnderlyingType {
        return GraphQLUnderlyingType(
            overallName = typeMappingDefinition.overallName,
            underlyingName = typeMappingDefinition.underlyingName,
        )
    }
}

