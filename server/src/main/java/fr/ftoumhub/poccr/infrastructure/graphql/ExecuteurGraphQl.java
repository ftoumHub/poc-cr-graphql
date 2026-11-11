package fr.ftoumhub.poccr.infrastructure.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.validation.ValidationEnCours;
import fr.ftoumhub.poccr.domaine.validation.VariablesDocument;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exécute la requête du document dans le moteur GraphQL de l'application (sans passer par HTTP),
 * avec les mêmes DataLoaders et la même gestion d'erreurs que les appels du plugin.
 *
 * Les variables {@code $id} (patient) et {@code $doc} (document) sont imposées par le serveur ; la
 * requête n'en déclare que celles des racines qu'elle utilise, les autres sont ignorées. La validation
 * en cours est placée dans le contexte GraphQL pour alimenter {@code document.validation}.
 */
public class ExecuteurGraphQl implements ExecuteurRequetesDossier {

    private static final String OPERATION_ATTENDUE = "DonneesDocument";
    private static final Duration DELAI = Duration.ofSeconds(10);
    private static final List<String> RACINES = List.of("patient", "document");

    private final ExecutionGraphQlService graphQl;
    private final ObjectMapper jsonCanonique;

    public ExecuteurGraphQl(ExecutionGraphQlService graphQl, ObjectMapper objectMapper) {
        this.graphQl = graphQl;
        this.jsonCanonique = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    @Override
    public String executer(String requete, VariablesDocument variables, ValidationEnCours enCours) {
        if (requete == null || !requete.trim().startsWith("query " + OPERATION_ATTENDUE)) {
            throw new RequeteRejetee("Seule l'opération " + OPERATION_ATTENDUE + " générée par l'éditeur est acceptée");
        }
        DefaultExecutionGraphQlRequest demande = new DefaultExecutionGraphQlRequest(
                requete, OPERATION_ATTENDUE,
                Map.of("id", variables.patientId().toString(), "doc", variables.documentId().toString()), null,
                "validation-" + UUID.randomUUID(), Locale.FRANCE);
        demande.configureExecutionInput((entree, constructeur) -> constructeur
                .graphQLContext(contexte -> contexte.put(ControleurDocument.CLE_VALIDATION_EN_COURS, enCours))
                .build());
        ExecutionGraphQlResponse reponse = graphQl.execute(demande).block(DELAI);

        if (reponse == null) {
            throw new RequeteRejetee("Aucune réponse du moteur GraphQL");
        }
        if (!reponse.getErrors().isEmpty()) {
            throw new RequeteRejetee(reponse.getErrors().stream()
                    .map(ResponseError::getMessage)
                    .collect(Collectors.joining(" ; ")));
        }
        Map<String, Object> data = reponse.getData();
        if (data == null || RACINES.stream().noneMatch(data::containsKey)) {
            throw new RequeteRejetee("La requête ne demande ni le patient ni le document");
        }
        if (data.containsKey("patient") && data.get("patient") == null) {
            throw new RequeteRejetee("Patient introuvable : " + variables.patientId());
        }
        if (data.containsKey("document") && data.get("document") == null) {
            throw new RequeteRejetee("Document introuvable : " + variables.documentId());
        }
        try {
            return jsonCanonique.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sérialisation de la réponse impossible", e);
        }
    }
}
