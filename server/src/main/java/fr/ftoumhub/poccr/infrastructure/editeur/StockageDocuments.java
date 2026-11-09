package fr.ftoumhub.poccr.infrastructure.editeur;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Stockage fichier des documents du POC. Le nom est contrôlé pour interdire toute traversée de chemin. */
public class StockageDocuments {

    private static final Pattern NOM_VALIDE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String EXTENSION = ".docx";
    private static final Pattern PATIENT_DANS_TAG = Pattern.compile("\"p\":\"([0-9a-fA-F-]{36})\"");

    private final Path dossier;

    public StockageDocuments(Path dossier) {
        this.dossier = dossier;
        try {
            Files.createDirectories(dossier);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> lister() {
        try (Stream<Path> fichiers = Files.list(dossier)) {
            return fichiers.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(EXTENSION))
                    .map(n -> n.substring(0, n.length() - EXTENSION.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void creerVierge(String nom) {
        Path fichier = chemin(nom);
        if (Files.exists(fichier)) {
            throw new IllegalStateException("Le document existe déjà : " + nom);
        }
        try {
            Files.write(fichier, DocumentVierge.octets());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] lire(String nom) {
        try {
            return Files.readAllBytes(chemin(nom));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void renommer(String ancien, String nouveau) {
        Path source = chemin(ancien);
        if (!Files.exists(source)) {
            throw new DocumentIntrouvable(ancien);
        }
        Path cible = chemin(nouveau);
        if (Files.exists(cible)) {
            throw new IllegalStateException("Un document nommé " + nouveau + " existe déjà");
        }
        try {
            Files.move(source, cible, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copie un document (validé ou non) sous un nouveau nom, sans sa validation : la copie
     * démarre comme un brouillon éditable, qui réutilise les content controls de la source.
     */
    public void dupliquer(String source, String nouveau) {
        Path chemin = chemin(source);
        if (!Files.exists(chemin)) {
            throw new DocumentIntrouvable(source);
        }
        Path cible = chemin(nouveau);
        if (Files.exists(cible)) {
            throw new IllegalStateException("Un document nommé " + nouveau + " existe déjà");
        }
        try {
            Files.copy(chemin, cible);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Patient référencé par la première liaison trouvée dans le docx, pour les brouillons :
     * tant qu'un document n'est pas validé, le serveur n'a cette information nulle part
     * ailleurs (elle n'existe que dans les tags des content controls). Lecture au mieux :
     * un docx illisible ou sans liaison renvoie vide plutôt que de faire échouer l'affichage.
     */
    public Optional<UUID> patientLie(String nom) {
        try (ZipFile zip = new ZipFile(chemin(nom).toFile())) {
            ZipEntry entree = zip.getEntry("word/document.xml");
            if (entree == null) {
                return Optional.empty();
            }
            String xml = new String(zip.getInputStream(entree).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("&quot;", "\"");
            Matcher m = PATIENT_DANS_TAG.matcher(xml);
            if (!m.find()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(m.group(1)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void remplacer(String nom, byte[] contenu) {
        Path fichier = chemin(nom);
        try {
            Path temporaire = Files.createTempFile(dossier, nom, ".tmp");
            Files.write(temporaire, contenu);
            Files.move(temporaire, fichier, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Clé de version exigée par OnlyOffice : change à chaque sauvegarde. */
    public String cleVersion(String nom) {
        try {
            return nom + "-" + Files.getLastModifiedTime(chemin(nom)).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path chemin(String nom) {
        if (nom == null || !NOM_VALIDE.matcher(nom).matches()) {
            throw new IllegalArgumentException("Nom de document invalide : " + nom);
        }
        return dossier.resolve(nom + EXTENSION);
    }

    public static final class DocumentIntrouvable extends RuntimeException {
        public DocumentIntrouvable(String nom) {
            super("Document introuvable : " + nom);
        }
    }
}
