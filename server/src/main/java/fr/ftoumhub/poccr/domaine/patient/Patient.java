package fr.ftoumhub.poccr.domaine.patient;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Patient tel que vu par la rédaction d'un compte rendu.
 * Aucune annotation de framework : le domaine reste indépendant de Spring.
 *
 * @param nom               nom d'usage
 * @param nomNaissance      nom de naissance ; null = identique au nom d'usage
 * @param prenomsNaissance  prénoms de naissance (INS), séparés par des espaces ; null = prénom usuel
 * @param lieuNaissance     code INSEE de la commune de naissance (INS)
 * @param dateNaissanceAmo  date de naissance de la carte d'assurance maladie (JJ/MM/AAAA, éventuellement lunaire)
 * @param nir               matricule INS (NIR ou NIA) ; null = identité non qualifiée
 * @param medecinTraitantId professionnel de santé déclaré comme médecin traitant
 */
public record Patient(UUID id, Ipp ipp, String nom, String prenom, LocalDate dateNaissance, Sexe sexe,
                      String nomNaissance, String prenomsNaissance, String lieuNaissance, String dateNaissanceAmo,
                      String nir, String email, String nationalite, UUID medecinTraitantId) {

    public Patient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ipp, "ipp");
        Objects.requireNonNull(nom, "nom");
        Objects.requireNonNull(dateNaissance, "dateNaissance");
        if (sexe == null) {
            sexe = Sexe.INCONNU;
        }
    }

    public int ageLe(LocalDate date) {
        return Period.between(dateNaissance, date).getYears();
    }

    /**
     * Âge lisible, à la manière des comptes rendus : en jours avant un mois, en mois avant deux ans,
     * en années ensuite (« 12 jours », « 8 mois », « 45 ans »).
     */
    public String ageEnTexteLe(LocalDate date) {
        long jours = ChronoUnit.DAYS.between(dateNaissance, date);
        long mois = ChronoUnit.MONTHS.between(dateNaissance, date);
        if (mois < 1) {
            return jours + (jours > 1 ? " jours" : " jour");
        }
        if (mois < 24) {
            return mois + " mois";
        }
        int ans = ageLe(date);
        return ans + " ans";
    }

    /** Nom de naissance ; le nom d'usage quand il n'a pas été saisi. */
    public String nomDeNaissance() {
        return nomNaissance == null || nomNaissance.isBlank() ? nom : nomNaissance;
    }

    /** Vrai quand le nom d'usage diffère du nom de naissance (« née DUBOIS »). */
    public boolean porteUnNomDUsage() {
        return !nom.equalsIgnoreCase(nomDeNaissance());
    }

    /** Titre de civilité ; sexe inconnu ou indéterminé : forme neutre, jamais un genre supposé. */
    public String titre() {
        return switch (sexe) {
            case FEMININ -> "Madame";
            case MASCULIN -> "Monsieur";
            case INDETERMINE, INCONNU -> "Madame, Monsieur";
        };
    }

    /** Identité nationale de santé ; absente tant que le matricule n'est pas connu. */
    public Optional<IdentiteNationaleSante> ins() {
        if (nir == null || nir.isBlank()) {
            return Optional.empty();
        }
        String prenoms = prenomsNaissance == null || prenomsNaissance.isBlank() ? prenom : prenomsNaissance;
        return Optional.of(new IdentiteNationaleSante(nir, nomDeNaissance(), prenoms, prenom, dateNaissance,
                lieuNaissance, sexe));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private Ipp ipp;
        private String nom;
        private String prenom;
        private LocalDate dateNaissance;
        private Sexe sexe = Sexe.INCONNU;
        private String nomNaissance;
        private String prenomsNaissance;
        private String lieuNaissance;
        private String dateNaissanceAmo;
        private String nir;
        private String email;
        private String nationalite;
        private UUID medecinTraitantId;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder ipp(Ipp ipp) { this.ipp = ipp; return this; }
        public Builder nom(String nom) { this.nom = nom; return this; }
        public Builder prenom(String prenom) { this.prenom = prenom; return this; }
        public Builder dateNaissance(LocalDate dateNaissance) { this.dateNaissance = dateNaissance; return this; }
        public Builder sexe(Sexe sexe) { this.sexe = sexe; return this; }
        public Builder nomNaissance(String nomNaissance) { this.nomNaissance = nomNaissance; return this; }
        public Builder prenomsNaissance(String prenomsNaissance) { this.prenomsNaissance = prenomsNaissance; return this; }
        public Builder lieuNaissance(String lieuNaissance) { this.lieuNaissance = lieuNaissance; return this; }
        public Builder dateNaissanceAmo(String dateNaissanceAmo) { this.dateNaissanceAmo = dateNaissanceAmo; return this; }
        public Builder nir(String nir) { this.nir = nir; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder nationalite(String nationalite) { this.nationalite = nationalite; return this; }
        public Builder medecinTraitantId(UUID medecinTraitantId) { this.medecinTraitantId = medecinTraitantId; return this; }

        public Patient construire() {
            return new Patient(id, ipp, nom, prenom, dateNaissance, sexe, nomNaissance, prenomsNaissance,
                    lieuNaissance, dateNaissanceAmo, nir, email, nationalite, medecinTraitantId);
        }
    }
}
