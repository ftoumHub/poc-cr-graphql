-- Codage PMSI de démo sur quelques séjours déjà posés en V3/V4, à la manière du corpus PARHAF
-- (projet PARTAGES, Health Data Hub) : type de compte rendu + diagnostic CIM-10 + acte CCAM.
--
-- Codes choisis pour leur cohérence narrative avec le `motif` déjà écrit, pas vérifiés contre
-- une nomenclature CIM-10/CCAM officielle (même réserve que celle déjà posée par le projet
-- parhaf-fhir-loader sur ses propres codes approximatifs, marqués TODO-VERIF) : fictifs, comme
-- le reste du jeu de données.
--
-- L'acte CCAM reste volontairement vide pour les séjours sans acte médico-chirurgical plausible
-- (les urgences sans geste, la goutte) : l'acte principal est facultatif dans PARHAF comme en
-- PMSI réel, on ne force pas une valeur partout.

-- MARTIN Claire — séjour en cours, Endocrinologie
update sejour set type_document = 'CRH',
                   diagnostic_cim10_code = 'E11.9', diagnostic_cim10_libelle = 'Diabète de type 2 sans complication',
                   acte_ccam_code = 'EQLF001', acte_ccam_libelle = 'Surveillance glycémique continue'
where numero = 'V2026000101';

-- MARTIN Claire — séjour terminé, Maladies infectieuses
update sejour set type_document = 'CRH',
                   diagnostic_cim10_code = 'J18.9', diagnostic_cim10_libelle = 'Pneumopathie, sans précision',
                   acte_ccam_code = 'ZBQK002', acte_ccam_libelle = 'Radiographie du thorax de face'
where numero = 'V2026000042';

-- MARTINEZ Hugo — consultation de cardiologie
update sejour set type_document = 'CRC',
                   diagnostic_cim10_code = 'I48.9', diagnostic_cim10_libelle = 'Fibrillation et flutter auriculaires, sans précision',
                   acte_ccam_code = 'DEQP003', acte_ccam_libelle = 'Électrocardiographie'
where numero = 'V2026000077';

-- DUPONT Camille — urgences en cours, pas de geste associé
update sejour set type_document = 'URGENCES',
                   diagnostic_cim10_code = 'R50.9', diagnostic_cim10_libelle = 'Fièvre, sans précision'
where numero = 'V2026000201';

-- LEFEBVRE Jean — séjour en cours, Cardiologie
update sejour set type_document = 'CRH',
                   diagnostic_cim10_code = 'I50.9', diagnostic_cim10_libelle = 'Insuffisance cardiaque, sans précision',
                   acte_ccam_code = 'DZQM006', acte_ccam_libelle = 'Échocardiographie transthoracique'
where numero = 'V2026000190';

-- LEFEBVRE Jean — séjour terminé, Médecine interne, pas de geste associé
update sejour set type_document = 'CRH',
                   diagnostic_cim10_code = 'M10.9', diagnostic_cim10_libelle = 'Goutte, sans précision'
where numero = 'V2026000060';
