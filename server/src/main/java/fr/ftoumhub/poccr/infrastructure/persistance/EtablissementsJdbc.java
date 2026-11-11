package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EtablissementsJdbc implements Etablissements {

    private static final RowMapper<Etablissement> LIGNE_VERS_ETABLISSEMENT = (rs, i) -> Etablissement.builder()
            .id(rs.getObject("id", UUID.class))
            .finess(rs.getString("finess"))
            .siret(rs.getString("siret"))
            .libelle(rs.getString("libelle"))
            .email(rs.getString("email"))
            .construire();

    private final JdbcClient jdbc;

    public EtablissementsJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Etablissement> parIds(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select id, finess, siret, libelle, email from etablissement where id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query(LIGNE_VERS_ETABLISSEMENT)
                .list()
                .stream()
                .collect(Collectors.toMap(Etablissement::id, Function.identity()));
    }

    /** Le POC n'a qu'un établissement ; s'il y en avait plusieurs, le premier par FINESS fait foi. */
    @Override
    public Etablissement parDefaut() {
        return jdbc.sql("select id, finess, siret, libelle, email from etablissement order by finess limit 1")
                .query(LIGNE_VERS_ETABLISSEMENT)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Aucun établissement en base (migration V7 non appliquée ?)"));
    }
}
