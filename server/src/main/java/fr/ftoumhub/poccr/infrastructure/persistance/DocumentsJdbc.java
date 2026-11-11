package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Destinataire;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.RoleDestinataire;
import fr.ftoumhub.poccr.domaine.document.Signataire;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DocumentsJdbc implements Documents {

    private static final String COLONNES =
            "id, nom, titre, patient_id, etablissement_id, auteur_id, cree_le, date_valeur";

    private static final RowMapper<Document> LIGNE_VERS_DOCUMENT = (rs, i) -> Document.builder()
            .id(rs.getObject("id", UUID.class))
            .nom(new NomDocument(rs.getString("nom")))
            .titre(rs.getString("titre"))
            .patientId(rs.getObject("patient_id", UUID.class))
            .etablissementId(rs.getObject("etablissement_id", UUID.class))
            .auteurId(rs.getObject("auteur_id", UUID.class))
            .creeLe(instant(rs.getTimestamp("cree_le")))
            .dateValeur(instant(rs.getTimestamp("date_valeur")))
            .construire();

    private record LigneSignataire(UUID documentId, Signataire signataire) {
    }

    private record LigneDestinataire(UUID documentId, Destinataire destinataire) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public DocumentsJdbc(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Optional<Document> parNom(NomDocument nom) {
        return jdbc.sql("select " + COLONNES + " from document where nom = :nom")
                .param("nom", nom.valeur())
                .query(LIGNE_VERS_DOCUMENT)
                .optional();
    }

    @Override
    public Map<UUID, Document> parIds(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select " + COLONNES + " from document where id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query(LIGNE_VERS_DOCUMENT)
                .list()
                .stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
    }

    @Override
    public List<Document> tous() {
        return jdbc.sql("select " + COLONNES + " from document order by nom").query(LIGNE_VERS_DOCUMENT).list();
    }

    @Override
    public void enregistrer(Document d) {
        jdbc.sql("""
                        insert into document (id, nom, titre, patient_id, etablissement_id, auteur_id, cree_le, date_valeur)
                        values (:id, :nom, :titre, :patient, :etablissement, :auteur, :creeLe, :dateValeur)
                        on conflict (id) do update set
                            nom = excluded.nom, titre = excluded.titre, patient_id = excluded.patient_id,
                            etablissement_id = excluded.etablissement_id, auteur_id = excluded.auteur_id,
                            cree_le = excluded.cree_le, date_valeur = excluded.date_valeur
                        """)
                .param("id", d.id())
                .param("nom", d.nom().valeur())
                .param("titre", d.titre())
                .param("patient", d.patientId())
                .param("etablissement", d.etablissementId())
                .param("auteur", d.auteurId())
                .param("creeLe", Timestamp.from(d.creeLe()))
                .param("dateValeur", d.dateValeur() == null ? null : Timestamp.from(d.dateValeur()))
                .update();
    }

    @Override
    public void supprimer(UUID documentId) {
        // signataires et destinataires suivent (on delete cascade)
        jdbc.sql("delete from document where id = :id").param("id", documentId).update();
    }

    @Override
    public Map<UUID, ActeursDocument> acteursDe(Set<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(documentIds);
        Map<UUID, List<Signataire>> signataires = jdbc.sql("""
                        select document_id, professionnel_id, rang from document_signataire
                        where document_id in (:ids)
                        """)
                .param("ids", ids)
                .query((rs, i) -> new LigneSignataire(rs.getObject("document_id", UUID.class),
                        new Signataire(rs.getObject("professionnel_id", UUID.class), rs.getInt("rang"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(LigneSignataire::documentId,
                        Collectors.mapping(LigneSignataire::signataire, Collectors.toList())));
        Map<UUID, List<Destinataire>> destinataires = jdbc.sql("""
                        select document_id, role, rang, professionnel_id, nom, prenom, email, id_exemplaire
                        from document_destinataire
                        where document_id in (:ids)
                        """)
                .param("ids", ids)
                .query((rs, i) -> {
                    RoleDestinataire role = RoleDestinataire.valueOf(rs.getString("role"));
                    DestinataireExterieur exterieur = role == RoleDestinataire.EXTERIEUR
                            ? new DestinataireExterieur(rs.getString("nom"), rs.getString("prenom"), rs.getString("email"))
                            : null;
                    return new LigneDestinataire(rs.getObject("document_id", UUID.class), new Destinataire(role,
                            rs.getInt("rang"), rs.getObject("professionnel_id", UUID.class), exterieur,
                            rs.getString("id_exemplaire")));
                })
                .list()
                .stream()
                .collect(Collectors.groupingBy(LigneDestinataire::documentId,
                        Collectors.mapping(LigneDestinataire::destinataire, Collectors.toList())));

        Map<UUID, ActeursDocument> resultat = new HashMap<>();
        for (UUID id : ids) {
            resultat.put(id, ActeursDocument.reconstitues(
                    signataires.getOrDefault(id, List.of()), destinataires.getOrDefault(id, List.of())));
        }
        return resultat;
    }

    @Override
    public void remplacerActeurs(UUID documentId, ActeursDocument acteurs) {
        transaction.executeWithoutResult(statut -> {
            jdbc.sql("delete from document_signataire where document_id = :id").param("id", documentId).update();
            jdbc.sql("delete from document_destinataire where document_id = :id").param("id", documentId).update();
            for (Signataire s : acteurs.signataires()) {
                jdbc.sql("""
                                insert into document_signataire (document_id, professionnel_id, rang)
                                values (:document, :professionnel, :rang)
                                """)
                        .param("document", documentId)
                        .param("professionnel", s.professionnelId())
                        .param("rang", s.rang())
                        .update();
            }
            for (Destinataire d : acteurs.destinataires()) {
                DestinataireExterieur e = d.exterieur();
                jdbc.sql("""
                                insert into document_destinataire
                                    (id, document_id, role, rang, professionnel_id, nom, prenom, email, id_exemplaire)
                                values (:id, :document, :role, :rang, :professionnel, :nom, :prenom, :email, :exemplaire)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("document", documentId)
                        .param("role", d.role().name())
                        .param("rang", d.rang())
                        .param("professionnel", d.professionnelId())
                        .param("nom", e == null ? null : e.nom())
                        .param("prenom", e == null ? null : e.prenom())
                        .param("email", e == null ? null : e.email())
                        .param("exemplaire", d.idExemplaire())
                        .update();
            }
        });
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
