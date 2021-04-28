package graphql.nadel.enginekt.blueprint

data class GraphQLUnderlyingField(
    val parentTypeName: String,
    val overallName: String,
    val underlyingName: String,
)
