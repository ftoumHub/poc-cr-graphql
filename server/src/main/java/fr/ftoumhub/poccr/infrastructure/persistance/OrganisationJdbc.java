package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.organisation.Service;
import fr.ftoumhub.poccr.domaine.port.Organisation;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/** Organisation des établissements : quelques dizaines de lignes, relues à chaque appel. */
public class OrganisationJdbc implements Organisation {

    private final JdbcClient jdbc;

    public OrganisationJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Etablissement> etablissements() {
        return jdbc.sql("select id, finess, siret, libelle, email from etablissement order by finess")
                .query((rs, i) -> Etablissement.builder().id(rs.getObject("id", UUID.class)).finess(rs.getString("finess"))
                        .siret(rs.getString("siret")).libelle(rs.getString("libelle")).email(rs.getString("email"))
                        .construire())
                .list();
    }

    @Override
    public List<Service> services() {
        return jdbc.sql("select code, etablissement_id, libelle from service order by libelle")
                .query((rs, i) -> new Service(rs.getString("code"), rs.getObject("etablissement_id", UUID.class),
                        rs.getString("libelle")))
                .list();
    }

    @Override
    public List<UniteFonctionnelle> unitesFonctionnelles() {
        return jdbc.sql("""
                        select u.code, u.libelle, u.telephone, u.fax, u.service_code, s.libelle as service
                        from unite_fonctionnelle u join service s on s.code = u.service_code
                        order by u.libelle
                        """)
                .query((rs, i) -> new UniteFonctionnelle(rs.getString("code"), rs.getString("libelle"),
                        rs.getString("telephone"), rs.getString("fax"), rs.getString("service_code"),
                        rs.getString("service")))
                .list();
    }
}
