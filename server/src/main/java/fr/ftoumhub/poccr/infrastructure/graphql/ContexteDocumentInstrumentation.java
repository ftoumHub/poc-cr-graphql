package fr.ftoumhub.poccr.infrastructure.graphql;

import graphql.ExecutionInput;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Place dans le contexte GraphQL le séjour figé sur le document courant (clé de contexte).
 *
 * Le plugin envoie toujours la variable {@code doc} (et la validation l'impose), même quand la requête ne
 * déclare que la racine patient. Le séjour du document est lu ici, pour toutes les exécutions (HTTP du
 * plugin comme réexécution à la validation) : {@code Patient.sejourCourant} renvoie alors ce séjour, sans
 * modification du plugin ni du format des liaisons. Le séjour n'est retenu que s'il appartient au patient
 * interrogé (voir Sejour.deReference).
 */
public class ContexteDocumentInstrumentation implements Instrumentation {

    public static final String CLE_SEJOUR_DU_DOCUMENT = "poc.sejourDuDocument";

    private final Function<UUID, Optional<UUID>> sejourDuDocument;

    public ContexteDocumentInstrumentation(Function<UUID, Optional<UUID>> sejourDuDocument) {
        this.sejourDuDocument = sejourDuDocument;
    }

    @Override
    public ExecutionInput instrumentExecutionInput(ExecutionInput entree, InstrumentationExecutionParameters parametres,
                                                   InstrumentationState etat) {
        Object doc = entree.getVariables().get("doc");
        if (doc == null) {
            return entree;
        }
        UUID documentId;
        try {
            documentId = UUID.fromString(doc.toString());
        } catch (IllegalArgumentException e) {
            return entree;   // identifiant illisible : la requête échouera d'elle-même sur document(id)
        }
        sejourDuDocument.apply(documentId)
                .ifPresent(sejour -> entree.getGraphQLContext().put(CLE_SEJOUR_DU_DOCUMENT, sejour));
        return entree;
    }
}
