-- Codage PMSI du séjour (diagnostic principal CIM-10, acte principal CCAM) et type du compte
-- rendu qui en a été rédigé — inspiré du corpus PARHAF (projet PARTAGES, Health Data Hub),
-- dont chaque patient porte un `suggested_scenario.primary_diagnosis` / `primary_procedure`
-- et dont chaque document a un `type` (CRH, CRC, CRO, ANAPATH, URGENCES, ACCOUCHEMENT, MATERNITE).
--
-- Comme pour sejour.type (TypeSejour) : stockage en varchar, validation dans l'enum Java
-- correspondant, pas de contrainte d'énumération côté SQL (convention déjà suivie par le projet).
-- Les quatre colonnes sont nullables : un séjour sans codage PMSI reste un séjour valide.

alter table sejour
    add column diagnostic_cim10_code    varchar(10),   -- ex. 'J18.9' ; CIM-10, 3 à 7 caractères
    add column diagnostic_cim10_libelle varchar(300),
    add column acte_ccam_code           varchar(10),   -- CCAM : 4 lettres + 3 chiffres (ex. 'JJQP005')
    add column acte_ccam_libelle        varchar(300),
    add column type_document            varchar(30);   -- CRH, CRC, CRO, ANAPATH, URGENCES, ACCOUCHEMENT, MATERNITE

-- Requête épidémiologique la plus probable : « tous les séjours portant tel diagnostic ».
create index idx_sejour_diagnostic on sejour (diagnostic_cim10_code) where diagnostic_cim10_code is not null;
