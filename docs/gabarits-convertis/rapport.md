# Conversion des gabarits Velocity

| Document | Valeurs | Composés en ligne | Composés bloc | Valeurs dans les composés | Conditions | Boucles (répét./liaison/échec) | À reprendre | Tag max |
|---|---|---|---|---|---|---|---|---|
| 01-19501-contenu-p-aspose-foss.docx | 42 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 0 | 310 |
| 02-2371079234-contenu-p-aspose-foss.docx | 523 | 3 | 12 | 92 | 3 | 17 / 198 / 0 | 10 | 1994 |
| 03-2371615284-contenu-p-aspose-foss.docx | 303 | 3 | 12 | 92 | 3 | 18 / 185 / 0 | 0 | 1969 |
| 04-1382913-contenu-p-aspose-foss.docx | 100 | 20 | 0 | 70 | 20 | 0 / 20 / 0 | 0 | 926 |
| 05-2691-contenu-p-aspose-foss.docx | 65 | 68 | 1 | 1 | 93 | 0 / 9 / 0 | 0 | 1068 |
| 06-25981-contenu-p-aspose-foss.docx | 21 | 0 | 0 | 0 | 0 | 0 / 2 / 0 | 0 | 253 |
| 07-2371086220-contenu-p-aspose-foss.docx | 748 | 7 | 23 | 166 | 2 | 32 / 42 / 0 | 10 | 691 |
| 08-1749225460-contenu-p-aspose-foss.docx | 155 | 0 | 0 | 0 | 0 | 0 / 2 / 0 | 1 | 345 |
| 09-1749219460-contenu-a-aspose-foss.docx | 155 | 0 | 0 | 0 | 0 | 0 / 2 / 0 | 1 | 345 |
| 10-46401-contenu-p-aspose-foss.docx | 3 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 0 | 175 |
| 11-46403-contenu-p-aspose-foss.docx | 3 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 0 | 175 |
| 12-1747936466-contenu-p-aspose-foss.docx | 21 | 0 | 0 | 0 | 0 | 0 / 2 / 0 | 0 | 253 |
| 13-1747562461-contenu-a-aspose-foss.docx | 21 | 0 | 0 | 0 | 0 | 0 / 2 / 0 | 0 | 253 |
| 14-662090641-contenu-p-aspose-foss.docx | 0 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 9 | 0 |
| 15-1168918-contenu-a-aspose-foss.docx | 205 | 9 | 10 | 129 | 19 | 10 / 3 / 0 | 13 | 1579 |
| 16-1189926-contenu-a-aspose-foss.docx | 190 | 8 | 10 | 128 | 18 | 10 / 3 / 0 | 13 | 1579 |
| 17-1748256464-contenu-a-aspose-foss.docx | 53 | 0 | 1 | 4 | 0 | 1 / 4 / 1 | 12 | 534 |
| 18-1748243462-contenu-a-aspose-foss.docx | 39 | 1 | 1 | 11 | 0 | 2 / 3 / 1 | 5 | 581 |
| 19-48407-contenu-p-aspose-foss.docx | 12 | 0 | 0 | 0 | 0 | 0 / 0 / 0 | 0 | 309 |
| 20-1289917-contenu-a-aspose-foss.docx | 126 | 4 | 10 | 134 | 14 | 10 / 3 / 0 | 8 | 2100 |

## À reprendre (par raison)

- 30 × image
- 16 × Dossier technique RADIO : hors périmètre du contrat CDF
- 12 × Méta-données du multicontexte
- 10 × variable de boucle utilisée hors de sa boucle
- 3 × Formulaire : chemin non repris .getPrescriptionLigne().getLibelleLignePres()
- 3 × Formulaire : chemin non repris .getPrescriptionLigne().getPrpl_txt_poso_Formatte()
- 3 × Contexte recueils actes et diag : domaine RDV / RADIO hors périmètre
- 2 × Séjour : chemin non repris *.ide_pecs*.ide_organisme.orga_nom
- 1 × Formulaire : chemin non repris .getDateValidation()
- 1 × Formulaire : chemin non repris .getUtilisateurValidant()
- 1 × Contexte dossier ris : domaine RDV / RADIO hors périmètre

## Avertissements

- 8 × contexte d'observations sans chapitre identifiable : les 5 plus récentes, tous chapitres
- 1 × structure sur plusieurs paragraphes non contigus : dissoute (valeurs jointes)
- 1 × directive écrite « #$foreach » (anomalie du gabarit d'origine) : traitée comme #foreach
- 1 × directive écrite « #$end » (anomalie du gabarit d'origine) : traitée comme #end
