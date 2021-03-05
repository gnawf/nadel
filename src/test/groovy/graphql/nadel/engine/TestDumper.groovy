package graphql.nadel.engine

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.ObjectTypeDefinition
import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.dsl.ExtendedFieldDefinition
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.RemoteArgumentSource
import graphql.nadel.testutils.TestUtil
import graphql.parser.Parser
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.TypeUtil

import java.text.Normalizer
import java.util.regex.Pattern

import static graphql.schema.idl.SchemaPrinter.Options.defaultOptions

class TestDumper {
    static final Set<String> queries = new HashSet<>()

    static def getServiceExecutionResults() {
        return ServiceExecutionResult.results
    }

    static def getServices() {
        return Service.services
    }

    static def getOverallSchema() {
        return NadelExecutionStrategy.lastOverallSchema
    }

    static def printQueryCompact(Document document) {
        queries.add(AstPrinter.printAst(document).toString().trim())
        return AstPrinter.printAstCompact(document)
    }

    static String getTestName() {
        def stackTraceElement = new RuntimeException().stackTrace.find {
            it.className.startsWith("graphql.nadel") && (it.className.endsWith("Test") || it.className.endsWith("Test2") || it.className.endsWith("Test3"))
        }
        def fileName = stackTraceElement.className.replace(".", "/") + ".groovy"
        def file = new File("src/test/groovy/" + fileName)
        if (file.exists()) {
            def lines = file.readLines()
            for (def i = stackTraceElement.lineNumber; i >= 0; i--) {
                if (lines[i].startsWith('    def "')) {
                    return lines[i].replaceFirst(Pattern.compile(/\s+def\s+"(.+)"\s*\(\)\s*\{/), "\$1")
                }
            }
        } else {
            throw new FileNotFoundException(file.absolutePath)
        }

        throw new UnsupportedOperationException("Unable to get file name")
    }

    private static final SchemaPrinter.Options printOptions = defaultOptions()
            .useAstDefinitions(false)
            .includeDirectives(true)
            .includeIntrospectionTypes(false)
            .includeScalarTypes(true)
            .includeSchemaDefinition(true)
            .includeDirectives {
                it.name != "include" && it.name != "skip" && it.name != "hydrated" && it.name != "renamed" && it.name != "deprecated" && it.name != "specifiedBy"
            }
            .includeSchemaElement {
                if (it instanceof GraphQLInputObjectType) {
                    it.name != "NadelHydrationArgument"
                } else {
                    true
                }
            }

    static void dump(String testName) {
        println "======================================================="
        println ""
        println "Dumping $testName"


        def incomingQuery = AstPrinter.printAst(
                new Parser().parseDocument(Objects.requireNonNull(NadelExecutionStrategyTest.incomingQuery, "No query"))
        ).toString().trim()
        println ""
        println "Incoming query:"
        println ""
        println incomingQuery

        println ""
        println "Underlying service queries:"
        println ""
        println queries.toString()

        DefaultPrettyPrinter pp = new DefaultPrettyPrinter()
        pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
        pp.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)

        def underlyingServiceResults = serviceExecutionResults.collect {
            new ObjectMapper()
                    .writer(pp)
                    .writeValueAsString(it)
        }
        println ""
        println "Underlying service results:"
        println ""
        println underlyingServiceResults.toString()

        def overallResponse = new ObjectMapper()
                .writer(pp)
                .writeValueAsString(NadelExecutionStrategy.lastResp.toSpecification())

        println ""
        println "Overall response:"
        println ""
        println overallResponse

        println ""
        println "Had services:"
        println ""
        println services.collect {
            it.name
        }.toString()

        println ""
        println "Had overall schema:"
        println ""
        def underlyingSchemas = services.collect {
            new SchemaPrinter(printOptions).print(it.underlyingSchema)
        }
        println(underlyingSchemas)

        println ""
        println "Had overall schema:"
        println ""
        def schemaPrinted = new SchemaPrinter(printOptions).print(overallSchema)
        println(schemaPrinted)

        println ""
        println "Transforming schema"
        println ""
        def transformedSchema = schemaPrinted
        listOfTransforms.forEach { transform ->
            if (transform.startsWith("add-type-directive\n")) {
                def (transformType, typeName, directiveStr) = transform.split("\n")
                println("Finding $typeName and applying directive $directiveStr")

                def schemaLines = transformedSchema.readLines()
                def changed = false
                schemaLines.eachWithIndex { line, index ->
                    if (line.startsWith("$typeName ")) {
                        schemaLines[index] = line.replace(" {", " $directiveStr {")
                        changed = true
                    }
                    return
                }
                if (!changed) {
                    throw new IllegalStateException("Unable to find $typeName")
                }
                transformedSchema = schemaLines.join("\n")
            } else if (transform.startsWith("change-field-def\n")) {
                def (transformType, typeName, String newDef) = transform.split("\n")
                println("Finding $typeName and appyling field def $newDef")
                def fieldName = newDef.substring(0, newDef.indexOf(':'))

                def schemaLines = transformedSchema.readLines()
                boolean inType = false
                boolean replaced = false // So lazy to replace with normal for loop
                schemaLines.eachWithIndex { line, index ->
                    if (replaced) {
                        return
                    }
                    if (line.startsWith("$typeName ")) {
                        inType = true
                    } else if (inType) {
                        if (line.trim().startsWith(fieldName + ":") || line.trim().startsWith(fieldName + "(") || line.trim().startsWith(fieldName + " ")) {
                            def numSpaces = line.indexOf(fieldName)
                            def spaces = new String(new char[numSpaces]).replace("\0", " ")
                            schemaLines[index] = spaces + newDef
                            replaced = true
                        }
                    }
                    if (inType && line.trim() == "}") {
                        throw new IllegalStateException("Unable to find $typeName")
                    }
                    return
                }
                transformedSchema = schemaLines.join("\n")
            } else if (transform.startsWith("add-field-def\n")) {
                def (transformType, typeName, String newDef) = transform.split("\n")
                println("Finding $typeName and adding field def $newDef")

                def schemaLines = transformedSchema.readLines()
                def typeDefLine = -1
                schemaLines.eachWithIndex { line, index ->
                    if (line.startsWith("$typeName ")) {
                        // Should break but ceebs changing
                        typeDefLine = index
                    }
                    return
                }
                if (typeDefLine < 0) {
                    throw new UnsupportedOperationException("No line to add to")
                }
                schemaLines.add(typeDefLine + 1, "  " + newDef)
                transformedSchema = schemaLines.join("\n")
            } else if (transform.startsWith("add-new-type\n")) {
                def (transformType, typeName, String newDef) = transform.split("\n", 3)

                def schemaLines = transformedSchema.readLines()
                def existingTypeStartIndex = -1
                schemaLines.eachWithIndex { line, index ->
                    if (line.startsWith("$typeName ")) {
                        existingTypeStartIndex = index
                    }
                    if (existingTypeStartIndex >= 0 && line.trim() == "}") {
                        def existingType = schemaLines.subList(existingTypeStartIndex, index + 1).join("\n")
                        if (existingType.trim() != newDef.trim()) {
                            System.err.println("Adding $typeName again")
                        }
                    }
                    return
                }

                transformedSchema = transformedSchema.trim() + "\n\n$newDef"
            } else {
                throw new UnsupportedOperationException("Unknown schema transform: $transform")
            }
        }
        println ""
        println("Transformed schema")
        println ""
        println transformedSchema

        println ""

        def dump = new Dump()
        dump.name = testName
        dump.query = incomingQuery
        dump.underlyingQueries = new HashMap<>().tap {
            queries.eachWithIndex { result, index ->
                put("call-" + index, result)
            }
        }
        dump.underlyingResults = new HashMap<>().tap {
            underlyingServiceResults.eachWithIndex { result, index ->
                put("call-" + index, result)
            }
        }
        dump.services = new HashMap<>().tap {
            TestDumper.services.forEach {
                put(it.name, new SchemaPrinter(TestDumper.printOptions).print(it.underlyingSchema))
            }
        }
        dump.schema = transformedSchema
        dump.response = overallResponse
        dump.ndsls = new HashMap<>().tap {
            TestUtil.prevServiceDSLs.forEach { key, value ->
                put(key, value.stripIndent().readLines().findAll {
                    !it.trim().isEmpty()
                }.join("\n"))
            }
        }

        def fixtureInYaml = new ObjectMapper(new YAMLFactory().tap {
            enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        }).findAndRegisterModules().writeValueAsString(dump)
        new File("src/test/fixtures/" + toSlug(testName) + ".yaml").write(fixtureInYaml)
        println fixtureInYaml

        println ""
        println "======================================================="
    }

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    public static String toSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("-")
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD)
        String slug = NONLATIN.matcher(normalized).replaceAll("")
        return slug.toLowerCase(Locale.ENGLISH)
    }

    static class Dump {
        String name
        String query
        Map<String, String> services
        Map<String, String> ndsls
        String schema
        Map<String, String> underlyingQueries
        Map<String, String> underlyingResults
        String response
    }

    private static List<String> getListOfTransforms() {
        def output = []

        overallSchema.allTypesAsList.forEach { type ->
            if (type instanceof GraphQLObjectType) {
                if (true) {
                    def definition = type.definition
                    if (definition instanceof ObjectTypeDefinitionWithTransformation) {
                        def underlyingName = definition.typeMappingDefinition.underlyingName
                        def overallName = definition.typeMappingDefinition.overallName
                        if (underlyingName != overallName) {
                            output.add("add-type-directive\ntype $overallName\n@renamed(from: \"$underlyingName\")")
                        }
                    }
                }

                List<List<String>> fieldsRequired = []

                if (true) {
                    type.fieldDefinitions.forEach { field ->
                        def definition = field.definition
                        if (definition == null) {
                            return
                        }

                        def withoutDirective = "change-field-def\ntype " + type.name + "\n" +
                                "${AstPrinter.printAst(definition)} "

                        if (definition instanceof ExtendedFieldDefinition && definition.fieldTransformation != null) {
                            if (definition.fieldTransformation.underlyingServiceHydration != null) {
                                def hydration = definition.fieldTransformation.underlyingServiceHydration

                                def hydrationPath = [
                                        hydration.syntheticField,
                                        hydration.topLevelField,
                                ].findAll { it != null }.collect { "\"$it\"" }.join(", ")
                                def hydrationArgs = hydration.arguments.collect {
                                    def value
                                    def argSource = it.remoteArgumentSource
                                    switch (argSource.sourceType) {
                                        case RemoteArgumentSource.SourceType.OBJECT_FIELD:
                                            def pathToField = argSource.path.collect { "\"$it\"" }.join(", ")
                                            fieldsRequired.add(argSource.path)
                                            value = "valueFromField: [$pathToField]"
                                            break
                                        case RemoteArgumentSource.SourceType.FIELD_ARGUMENT:
                                            value = "valueFromArg: \"${argSource.name}\""
                                            break
                                        default:
                                            throw new UnsupportedOperationException("no")
                                    }
                                    "{name: \"${it.name}\" $value}"
                                }.join(" ")

                                def extraArgs = ""
                                if (hydration.objectIdentifier != null) {
                                    extraArgs += " objectId: \"${hydration.objectIdentifier}\""
                                }
                                if (hydration.objectMatchByIndex) {
                                    extraArgs += " objectMatchByIndex: true"
                                }
                                if (hydration.batchSize != null) {
                                    extraArgs += " batchSize: \"${hydration.batchSize}\""
                                }

                                output.add(withoutDirective + "@hydrated(from: [$hydrationPath] arguments: [$hydrationArgs]$extraArgs)")
                            } else if (definition.fieldTransformation.fieldMappingDefinition != null) {
                                def rename = definition.fieldTransformation.fieldMappingDefinition
                                def underlyingPath = rename.inputPath
                                fieldsRequired.add(underlyingPath)
                                if (underlyingPath.size() == 1) {
                                    output.add(withoutDirective + "@renamed(from: \"${underlyingPath[0]}\")")
                                } else {
                                    def pullPath = underlyingPath.collect { "\"$it\"" }.join(", ")
                                    output.add(withoutDirective + "@pull(from: [$pullPath])")
                                }
                            }
                        }
                    }
                }

                if (true) {
                    fieldsRequired.forEach { pathToField ->
                        println "Creating instructions to pull field $pathToField"

                        GraphQLObjectType parentType = type
                        boolean isUnderlyingType = false
                        pathToField.eachWithIndex { fieldName, index ->
                            GraphQLFieldDefinition field
                            if (!isUnderlyingType)
                                parentType.fieldDefinitions.forEach {
                                    def fieldDef = it.definition
                                    if (fieldDef instanceof ExtendedFieldDefinition) {
                                        if (fieldDef.fieldTransformation.fieldMappingDefinition != null) {
                                            def underlyingPath = fieldDef.fieldTransformation.fieldMappingDefinition.inputPath
                                            if (underlyingPath.size() == 1 && underlyingPath[0] == fieldName) {
                                                field = it
                                            }
                                        }
                                    }
                                }
                            if (field == null) {
                                field = parentType.getFieldDefinition(fieldName)
                            }

                            def parentTypeDef = parentType.definition
                            def underlyingParentTypeName = parentType.name
                            if (parentTypeDef instanceof ObjectTypeDefinitionWithTransformation) {
                                underlyingParentTypeName = parentTypeDef.typeMappingDefinition.underlyingName
                            }

                            def underlyingParentType = isUnderlyingType ? parentType : services.collect {
                                return it.underlyingSchema.allTypesAsList.find {
                                    def definition = it.definition
                                    if (definition instanceof ObjectTypeDefinition) {
                                        if (definition.name == underlyingParentTypeName) {
                                            if (underlyingParentTypeName == "Query" || underlyingParentTypeName == "Mutation") {
                                                return definition.fieldDefinitions.any { it.name == fieldName }
                                            }
                                            return true
                                        }
                                    }

                                    return false
                                }
                            }.find { it != null } as GraphQLObjectType
                            def underlyingField = underlyingParentType.getFieldDefinition(fieldName)

                            if (underlyingField == null) {
                                throw new UnsupportedOperationException("Cannot find field at " + pathToField)
                            }

                            if (field == null || field.name != underlyingField.name || field.definition.type != underlyingField.definition.type) {
                                output.add("add-field-def\ntype $parentType.name\n" +
                                        "${AstPrinter.printAst(underlyingField.definition)} @omitted")
                            }

                            def overallTypeExists = !isUnderlyingType || overallSchema.allTypesAsList.any {
                                def itDef = it.definition
                                if (itDef instanceof ObjectTypeDefinitionWithTransformation) {
                                    itDef.typeMappingDefinition.underlyingName == underlyingParentType.name
                                }
                            }
                            if (!overallTypeExists) {
                                output.add("add-new-type\ntype $underlyingParentTypeName\n${AstPrinter.printAst(underlyingParentType.definition)}")
                            }

                            // At the end, don't bother finding the next parent type
                            if (index == pathToField.size() - 1) {
                                return
                            }

                            def nextParentTypeName = TypeUtil.unwrapAll(underlyingField.definition.type).name
                            // Handle underlying -> overall type name
//                            if (isUnderlyingType || field == null) {
                            def nextParent = null
                            services.forEach {
                                it.underlyingSchema.allTypesAsList.forEach {
                                    def definition = it.definition
                                    if (definition instanceof ObjectTypeDefinitionWithTransformation) {
                                        def mapping = definition.typeMappingDefinition
                                        if (mapping != null) {
                                            if (mapping.underlyingName == nextParentTypeName) {
                                                if (mapping.underlyingName != mapping.overallName && mapping.overallName != null) {
                                                    nextParent = overallSchema.getObjectType(mapping.overallName)
                                                    isUnderlyingType = false
                                                } else {
                                                    nextParent = it
                                                    isUnderlyingType = true
                                                }
                                            }
                                        }
                                    } else if (definition instanceof ObjectTypeDefinition) {
                                        if (definition.name == nextParentTypeName) {
                                            nextParent = it
                                            isUnderlyingType = true
                                        }
                                    }
                                }
                            }
                            if (nextParent == null) {
                                throw new UnsupportedOperationException("Where is my parent?")
                            }
                            parentType = nextParent
//                            }
//                            else {
//                                parentType = overallSchema.getType(nextParentTypeName) as GraphQLObjectType
//                                isUnderlyingType = false
//                                if (parentType == null) {
//                                    isUnderlyingType = true
//                                    parentType = services.collect {
//                                        it.underlyingSchema.allTypesAsList.find {
//                                            def definition = it.definition
//                                            if (definition instanceof ObjectTypeDefinitionWithTransformation) {
//                                                return definition.typeMappingDefinition.underlyingName == nextParentTypeName
//                                            } else if (definition instanceof ObjectTypeDefinition) {
//                                                return definition.name == nextParentTypeName
//                                            }
//
//                                            return false
//                                        }
//                                    }.find { it != null } as GraphQLObjectType
//                                }
//                            }
                        }
                    }
                }
            }
        }

        return output
    }

    static void reset() {
        ServiceExecutionResult.clear()
        Service.services.clear()
        queries.clear()
        NadelExecutionStrategy.lastOverallSchema = null
        NadelExecutionStrategyTest.incomingQuery = null
        NadelExecutionStrategy.lastResp = null
        TestUtil.prevServiceDSLs = null
    }
}
