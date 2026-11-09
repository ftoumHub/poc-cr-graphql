package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.domaine.observation.FenetreTemporelle.FenetreTemporelleInvalide;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;

/** Traduit les erreurs de domaine en erreurs GraphQL lisibles par le plugin. */
public class GestionErreursGraphQl extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof FenetreTemporelleInvalide) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .build();
        }
        return null;
    }
}
