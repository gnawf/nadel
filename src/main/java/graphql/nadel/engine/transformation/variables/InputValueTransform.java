package graphql.nadel.engine.transformation.variables;

import graphql.PublicSpi;

/**
 * Called to transform input type values from {@link graphql.nadel.engine.transformation.variables.VariablesTransformer}
 */
@PublicSpi
public interface InputValueTransform {
    /**
     * The callback method given a value and its input type information
     *
     * @param value          the value to transform
     * @param inputValueTree the type information
     * @return a possible new value
     */
    Object transformValue(Object value, InputValueTree inputValueTree);
}
