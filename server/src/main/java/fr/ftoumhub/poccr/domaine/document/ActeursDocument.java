package fr.ftoumhub.poccr.domaine.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signataires et destinataires d'un document, valeur immuable qui porte leurs règles :
 * <ul>
 *   <li>signataires : un professionnel signe au plus une fois ; rangs 1..n contigus, le rang 1 est le principal ;</li>
 *   <li>destinataires : au plus un principal, au plus une copie au patient ; un professionnel n'est
 *       destinataire qu'une fois (pas à la fois principal et en copie) ; rangs 1..n contigus par rôle.</li>
 * </ul>
 * Deux constructions : {@link #saisis} depuis une saisie (les rangs suivent l'ordre de saisie) et
 * {@link #reconstitues} depuis le stockage (les rangs sont contrôlés).
 */
public final class ActeursDocument {

    public static final ActeursDocument AUCUN = new ActeursDocument(List.of(), List.of());

    private final List<Signataire> signataires;
    private final List<Destinataire> destinataires;

    private ActeursDocument(List<Signataire> signataires, List<Destinataire> destinataires) {
        this.signataires = signataires.stream().sorted(Comparator.comparingInt(Signataire::rang)).toList();
        this.destinataires = destinataires.stream()
                .sorted(Comparator.comparing(Destinataire::role).thenComparingInt(Destinataire::rang)).toList();
        verifier();
    }

    /**
     * Saisie de la page hôte.
     *
     * @param signataires          dans l'ordre de signature : le premier est le signataire principal
     * @param destinatairePrincipal facultatif
     * @param copies               professionnels en copie, dans l'ordre d'affichage
     * @param exterieurs           destinataires saisis en texte libre
     * @param copieAuPatient       le patient du document reçoit une copie
     */
    public static ActeursDocument saisis(List<UUID> signataires, UUID destinatairePrincipal, List<UUID> copies,
                                         List<DestinataireExterieur> exterieurs, boolean copieAuPatient) {
        List<Signataire> s = new ArrayList<>();
        for (int i = 0; i < signataires.size(); i++) {
            s.add(new Signataire(signataires.get(i), i + 1));
        }
        List<Destinataire> d = new ArrayList<>();
        if (destinatairePrincipal != null) {
            d.add(Destinataire.professionnel(RoleDestinataire.PRINCIPAL, 1, destinatairePrincipal));
        }
        for (int i = 0; i < copies.size(); i++) {
            d.add(Destinataire.professionnel(RoleDestinataire.COPIE, i + 1, copies.get(i)));
        }
        for (int i = 0; i < exterieurs.size(); i++) {
            d.add(Destinataire.exterieur(i + 1, exterieurs.get(i)));
        }
        if (copieAuPatient) {
            d.add(Destinataire.patient());
        }
        return new ActeursDocument(s, d);
    }

    public static ActeursDocument reconstitues(Collection<Signataire> signataires, Collection<Destinataire> destinataires) {
        return new ActeursDocument(List.copyOf(signataires), List.copyOf(destinataires));
    }

    private void verifier() {
        Set<UUID> signes = new HashSet<>();
        for (Signataire s : signataires) {
            if (!signes.add(s.professionnelId())) {
                throw new ActeursInvalides("Un professionnel ne signe qu'une fois le même document");
            }
        }
        verifierRangsContigus(signataires.stream().map(Signataire::rang).toList(), "des signataires");

        Set<UUID> destinatairesPs = new HashSet<>();
        for (Destinataire d : destinataires) {
            if (d.professionnelId() != null && !destinatairesPs.add(d.professionnelId())) {
                throw new ActeursInvalides(
                        "Un professionnel n'est destinataire qu'une fois (pas à la fois principal et en copie)");
            }
        }
        Map<RoleDestinataire, List<Integer>> rangsParRole = destinataires.stream().collect(Collectors.groupingBy(
                Destinataire::role, Collectors.mapping(Destinataire::rang, Collectors.toList())));
        if (rangsParRole.getOrDefault(RoleDestinataire.PRINCIPAL, List.of()).size() > 1) {
            throw new ActeursInvalides("Un document n'a qu'un destinataire principal");
        }
        if (rangsParRole.getOrDefault(RoleDestinataire.PATIENT, List.of()).size() > 1) {
            throw new ActeursInvalides("Le patient ne reçoit qu'une copie");
        }
        rangsParRole.forEach((role, rangs) -> verifierRangsContigus(rangs, "des destinataires " + role));
    }

    private static void verifierRangsContigus(List<Integer> rangs, String quoi) {
        List<Integer> tries = rangs.stream().sorted().toList();
        for (int i = 0; i < tries.size(); i++) {
            if (tries.get(i) != i + 1) {
                throw new ActeursInvalides("Les rangs " + quoi + " doivent se suivre à partir de 1");
            }
        }
    }

    public List<Signataire> signataires() {
        return signataires;
    }

    public Optional<Signataire> signatairePrincipal() {
        return signataires.stream().filter(Signataire::estPrincipal).findFirst();
    }

    public List<Signataire> signatairesSecondaires() {
        return signataires.stream().filter(s -> !s.estPrincipal()).toList();
    }

    public List<Destinataire> destinataires() {
        return destinataires;
    }

    public Optional<Destinataire> destinatairePrincipal() {
        return deRole(RoleDestinataire.PRINCIPAL).stream().findFirst();
    }

    public List<Destinataire> copies() {
        return deRole(RoleDestinataire.COPIE);
    }

    public List<Destinataire> exterieurs() {
        return deRole(RoleDestinataire.EXTERIEUR);
    }

    public boolean copieAuPatient() {
        return !deRole(RoleDestinataire.PATIENT).isEmpty();
    }

    /** Tous les professionnels cités (signataires et destinataires), pour un chargement groupé. */
    public Set<UUID> professionnels() {
        Set<UUID> ids = signataires.stream().map(Signataire::professionnelId).collect(Collectors.toSet());
        destinataires.stream().map(Destinataire::professionnelId).filter(java.util.Objects::nonNull).forEach(ids::add);
        return ids;
    }

    private List<Destinataire> deRole(RoleDestinataire role) {
        return destinataires.stream().filter(d -> d.role() == role).toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ActeursDocument a && signataires.equals(a.signataires) && destinataires.equals(a.destinataires);
    }

    @Override
    public int hashCode() {
        return 31 * signataires.hashCode() + destinataires.hashCode();
    }

    @Override
    public String toString() {
        return "ActeursDocument[signataires=" + signataires + ", destinataires=" + destinataires + "]";
    }
}
