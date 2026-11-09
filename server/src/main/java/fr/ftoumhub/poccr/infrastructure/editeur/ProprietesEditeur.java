package fr.ftoumhub.poccr.infrastructure.editeur;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * @param urlDocumentServer       URL du Document Server vue par le navigateur (chargement de api.js)
 * @param urlServeurPourDocServer URL de ce serveur vue depuis le conteneur Document Server
 *                                (téléchargement du docx et callback de sauvegarde)
 * @param urlServeurPourNavigateur URL de ce serveur vue par le navigateur (chargement du plugin)
 * @param dossierDocuments        stockage des .docx du POC
 * @param guidPlugin              guid déclaré dans plugin/config.json
 */
@ConfigurationProperties(prefix = "poc.editeur")
public record ProprietesEditeur(String urlDocumentServer,
                                String urlServeurPourDocServer,
                                String urlServeurPourNavigateur,
                                Path dossierDocuments,
                                String guidPlugin) {
}
