package fr.ftoumhub.poccr.domaine.professionnel;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Professionnel de santé : signataire, destinataire, auteur ou valideur d'un document.
 * Exposé par le type GraphQL {@code Medecin} du contrat CDF.
 *
 * @param code   code interne, unique
 * @param rpps   numéro RPPS (11 chiffres) ; facultatif
 * @param titre  libellé d'édition (« Dr », « Pr »)
 */
public record ProfessionnelSante(UUID id,
                                 String code,
                                 String rpps,
                                 String adeli,
                                 Civilite civilite,
                                 String titre,
                                 String nom,
                                 String prenom,
                                 String specialites,
                                 String email,
                                 String qualite,
                                 TypeMedecin type) {

    /** Préfixe de l'identifiant national d'un professionnel enregistré au RPPS. */
    private static final String PREFIXE_RPPS = "8";

    public ProfessionnelSante {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(nom, "nom");
        Objects.requireNonNull(type, "type");
        if (rpps != null && !rpps.matches("\\d{11}")) {
            throw new IllegalArgumentException("RPPS invalide pour " + code + " : 11 chiffres attendus");
        }
    }

    /** Identifiant national de professionnel de santé : « 8 » suivi du RPPS ; absent sans RPPS. */
    public String identifiantNational() {
        return rpps == null ? null : PREFIXE_RPPS + rpps;
    }

    /** Libellé d'adressage : « Dr Sophie DURAND ». */
    public String libelle() {
        return Stream.of(titre, prenom, nom)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String code;
        private String rpps;
        private String adeli;
        private Civilite civilite;
        private String titre;
        private String nom;
        private String prenom;
        private String specialites;
        private String email;
        private String qualite;
        private TypeMedecin type;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder rpps(String rpps) { this.rpps = rpps; return this; }
        public Builder adeli(String adeli) { this.adeli = adeli; return this; }
        public Builder civilite(Civilite civilite) { this.civilite = civilite; return this; }
        public Builder titre(String titre) { this.titre = titre; return this; }
        public Builder nom(String nom) { this.nom = nom; return this; }
        public Builder prenom(String prenom) { this.prenom = prenom; return this; }
        public Builder specialites(String specialites) { this.specialites = specialites; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder qualite(String qualite) { this.qualite = qualite; return this; }
        public Builder type(TypeMedecin type) { this.type = type; return this; }

        public ProfessionnelSante construire() {
            return new ProfessionnelSante(id, code, rpps, adeli, civilite, titre, nom, prenom, specialites, email,
                    qualite, type);
        }
    }
}
