package fr.ftoumhub.poccr.domaine.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Destinataire d'un document, dans un rôle et à un rang (ordre d'affichage au sein du rôle).
 *
 * @param professionnelId renseigné pour PRINCIPAL et COPIE uniquement
 * @param exterieur       renseigné pour EXTERIEUR uniquement
 * @param idExemplaire    numéro d'exemplaire (contrat CDF) ; facultatif
 */
public record Destinataire(RoleDestinataire role,
                           int rang,
                           UUID professionnelId,
                           DestinataireExterieur exterieur,
                           String idExemplaire) {

    public Destinataire {
        Objects.requireNonNull(role, "role");
        if (rang < 1) {
            throw new ActeursInvalides("Le rang d'un destinataire commence à 1");
        }
        if (role.designeUnProfessionnel() != (professionnelId != null)) {
            throw new ActeursInvalides(role.designeUnProfessionnel()
                    ? "Un destinataire " + role + " désigne un professionnel de santé"
                    : "Un destinataire " + role + " ne désigne pas de professionnel de santé");
        }
        if ((role == RoleDestinataire.EXTERIEUR) != (exterieur != null)) {
            throw new ActeursInvalides("Seul un destinataire extérieur est saisi en texte libre");
        }
    }

    public static Destinataire professionnel(RoleDestinataire role, int rang, UUID professionnelId) {
        return new Destinataire(role, rang, professionnelId, null, null);
    }

    public static Destinataire exterieur(int rang, DestinataireExterieur exterieur) {
        return new Destinataire(RoleDestinataire.EXTERIEUR, rang, null, exterieur, null);
    }

    public static Destinataire patient() {
        return new Destinataire(RoleDestinataire.PATIENT, 1, null, null, null);
    }
}
