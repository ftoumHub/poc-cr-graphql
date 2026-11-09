package fr.ftoumhub.poccr.infrastructure.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exécute la requête du document dans le moteur GraphQL de l'application (sans passer par HTTP),
 * avec les mêmes DataLoaders et la même gestion d'erreurs que les appels du plugin.
 */
public class ExecuteurGraphQl implements ExecuteurRequetesDossier {

    private static final String OPERATION_ATTENDUE = "DonneesDocument";
    private static final Duration DELAI = Duration.ofSeconds(10);

    private final ExecutionGraphQlService graphQl;
    private final ObjectMapper jsonCanonique;

    public ExecuteurGraphQl(ExecutionGraphQlService graphQl, ObjectMapper objectMapper) {
        this.graphQl = graphQl;
        this.jsonCanonique = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    @Override
    public String executer(String requete, UUID patientId) {
        if (requete == null || !requete.trim().startsWith("query " + OPERATION_ATTENDUE)) {
            throw new RequeteRejetee("Seule l'opération " + OPERATION_ATTENDUE + " générée par l'éditeur est acceptée");
        }
        ExecutionGraphQlResponse reponse = graphQl.execute(new DefaultExecutionGraphQlRequest(
                        requete, OPERATION_ATTENDUE, Map.of("id", patientId.toString()), null,
                        "validation-" + UUID.randomUUID(), Locale.FRANCE))
                .block(DELAI);

        if (reponse == null) {
            throw new RequeteRejetee("Aucune réponse du moteur GraphQL");
        }
        if (!reponse.getErrors().isEmpty()) {
            throw new RequeteRejetee(reponse.getErrors().stream()
                    .map(ResponseError::getMessage)
                    .collect(Collectors.joining(" ; ")));
        }
        Map<String, Object> data = reponse.getData();
        if (data == null || data.get("patient") == null) {
            throw new RequeteRejetee("Patient introuvable : " + patientId);
        }
        try {
            return jsonCanonique.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sérialisation de la réponse impossible", e);
        }
    }
}
