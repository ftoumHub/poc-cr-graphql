package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.Civilite;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.professionnel.TypeMedecin;
import fr.ftoumhub.poccr.domaine.professionnel.TypeTelephone;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ProfessionnelsSanteJdbc implements ProfessionnelsSante {

    private static final String COLONNES =
            "id, code, rpps, adeli, civilite, titre, nom, prenom, specialites, email, qualite, type_medecin";

    private static final RowMapper<ProfessionnelSante> LIGNE_VERS_PROFESSIONNEL = (rs, i) -> {
        String civilite = rs.getString("civilite");
        return ProfessionnelSante.builder()
                .id(rs.getObject("id", UUID.class))
                .code(rs.getString("code"))
                .rpps(rs.getString("rpps"))
                .adeli(rs.getString("adeli"))
                .civilite(civilite == null ? null : Civilite.valueOf(civilite))
                .titre(rs.getString("titre"))
                .nom(rs.getString("nom"))
                .prenom(rs.getString("prenom"))
                .specialites(rs.getString("specialites"))
                .email(rs.getString("email"))
                .qualite(rs.getString("qualite"))
                .type(TypeMedecin.valueOf(rs.getString("type_medecin")))
                .construire();
    };

    private static final RowMapper<Adresse> LIGNE_VERS_ADRESSE = (rs, i) -> Adresse.builder()
            .id(rs.getObject("id", UUID.class))
            .proprietaireId(rs.getObject("proprietaire_id", UUID.class))
            .type(TypeAdresse.valueOf(rs.getString("type")))
            .ligne1(rs.getString("ligne1"))
            .ligne2(rs.getString("ligne2"))
            .ligne3(rs.getString("ligne3"))
            .codePostal(rs.getString("code_postal"))
            .ville(rs.getString("ville"))
            .pays(rs.getString("pays"))
            .construire();

    private final JdbcClient jdbc;

    public ProfessionnelsSanteJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, ProfessionnelSante> parIds(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select " + COLONNES + " from professionnel_sante where id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query(LIGNE_VERS_PROFESSIONNEL)
                .list()
                .stream()
                .collect(Collectors.toMap(ProfessionnelSante::id, Function.identity()));
    }

    @Override
    public List<ProfessionnelSante> rechercher(String texte, int limite) {
        String debut = texte.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("select " + COLONNES + """
                         from professionnel_sante
                        where lower(nom) like :debut or lower(prenom) like :debut or lower(code) like :debut
                        order by nom, prenom
                        limit :limite
                        """)
                .param("debut", debut)
                .param("limite", limite)
                .query(LIGNE_VERS_PROFESSIONNEL)
                .list();
    }

    @Override
    public Map<UUID, List<Adresse>> adressesDe(Set<UUID> proprietaireIds) {
        if (proprietaireIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        select id, coalesce(professionnel_id, etablissement_id) as proprietaire_id, type,
                               ligne1, ligne2, ligne3, code_postal, ville, pays
                        from adresse
                        where professionnel_id in (:ids) or etablissement_id in (:ids)
                        order by type
                        """)
                .param("ids", List.copyOf(proprietaireIds))
                .query(LIGNE_VERS_ADRESSE)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Adresse::proprietaireId));
    }

    @Override
    public Map<UUID, List<Telephone>> telephonesDe(Set<UUID> adresseIds) {
        if (adresseIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select id, adresse_id, type, numero from telephone where adresse_id in (:ids) order by type")
                .param("ids", List.copyOf(adresseIds))
                .query((rs, i) -> new Telephone(rs.getObject("id", UUID.class), rs.getObject("adresse_id", UUID.class),
                        TypeTelephone.valueOf(rs.getString("type")), rs.getString("numero")))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Telephone::adresseId));
    }
}
