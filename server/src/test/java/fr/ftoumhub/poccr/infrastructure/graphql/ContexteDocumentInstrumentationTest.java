package fr.ftoumhub.poccr.infrastructure.graphql;

import graphql.ExecutionInput;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContexteDocumentInstrumentationTest {

    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID SEJOUR = UUID.randomUUID();
    private final ContexteDocumentInstrumentation instrumentation = new ContexteDocumentInstrumentation(
            id -> id.equals(DOCUMENT) ? Optional.of(SEJOUR) : Optional.empty());

    private ExecutionInput entree(Map<String, Object> variables) {
        return ExecutionInput.newExecutionInput().query("query DonneesDocument { __typename }").variables(variables).build();
    }

    @Test
    void la_variable_doc_place_le_sejour_du_document_dans_le_contexte_meme_non_declaree() {
        ExecutionInput resultat = instrumentation.instrumentExecutionInput(entree(Map.of("doc", DOCUMENT.toString())), null, null);

        UUID sejour = resultat.getGraphQLContext().get(ContexteDocumentInstrumentation.CLE_SEJOUR_DU_DOCUMENT);
        assertThat(sejour).isEqualTo(SEJOUR);
    }

    @Test
    void sans_document_ou_avec_un_identifiant_illisible_le_contexte_reste_vide() {
        for (Map<String, Object> variables : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("doc", "pas-un-uuid"),
                Map.of("doc", UUID.randomUUID().toString()))) {
            ExecutionInput resultat = instrumentation.instrumentExecutionInput(entree(variables), null, null);
            assertThat((Object) resultat.getGraphQLContext().get(ContexteDocumentInstrumentation.CLE_SEJOUR_DU_DOCUMENT)).isNull();
        }
    }
}
