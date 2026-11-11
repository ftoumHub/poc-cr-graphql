-- Document, signataires, destinataires et leurs référentiels (établissement, professionnels de santé,
-- adresses, téléphones). Conception : docs/conception/2026-11-11-document-signataires-destinataires.md
--
-- Conventions du projet : UUID, varchar validé par un enum Java (pas de contrainte d'énumération SQL),
-- données entièrement fictives. Les règles d'unicité sont garanties ici en dernier ressort ; le domaine
-- les porte aussi, avec des messages métier. Le gel après validation, les rangs contigus et les
-- prérequis de validation (patient rattaché, signataire principal) ne relèvent que du domaine.

create table etablissement (
    id      uuid primary key,
    finess  varchar(9)   not null unique,
    siret   varchar(14),
    libelle varchar(200) not null,
    email   varchar(200)
);

create table professionnel_sante (
    id           uuid primary key,
    code         varchar(20)  not null unique,   -- code interne (Medecin.code)
    rpps         varchar(11)  unique,            -- identifiant national = '8' || rpps
    adeli        varchar(9),
    civilite     varchar(20),                    -- DOCTEUR, PROFESSEUR, MONSIEUR, MADAME
    titre        varchar(50),                    -- libellé d'édition : « Dr », « Pr »
    nom          varchar(100) not null,
    prenom       varchar(100),
    specialites  varchar(300),
    email        varchar(200),
    qualite      varchar(50),
    type_medecin varchar(20)  not null           -- TypeMedecin : ETABLISSEMENT, CORRESPONDANT
);
create index idx_ps_nom on professionnel_sante (lower(nom), lower(prenom));

create table adresse (
    id               uuid primary key,
    professionnel_id uuid references professionnel_sante (id) on delete cascade,
    etablissement_id uuid references etablissement (id) on delete cascade,
    type             varchar(20) not null,       -- TypeAdresse : PROFESSIONNELLE, PERSONNELLE, PRINCIPALE, NAISSANCE
    ligne1           varchar(100),
    ligne2           varchar(100),
    ligne3           varchar(100),
    code_postal      varchar(10),
    ville            varchar(100),
    pays             varchar(100),
    constraint adresse_un_seul_proprietaire check (num_nonnulls(professionnel_id, etablissement_id) = 1)
);
create unique index uq_adresse_ps on adresse (professionnel_id, type) where professionnel_id is not null;
create unique index uq_adresse_etablissement on adresse (etablissement_id, type) where etablissement_id is not null;

create table telephone (
    id         uuid primary key,
    adresse_id uuid        not null references adresse (id) on delete cascade,
    type       varchar(20) not null,             -- TypeTelephone : FIXE, PORTABLE, PERSONNEL, PRINCIPAL, FAX, AUTRE
    numero     varchar(30) not null,
    constraint uq_telephone_type unique (adresse_id, type)
);

create table document (
    id               uuid primary key,
    nom              varchar(64) not null unique,          -- = NomDocument, nom du fichier .docx
    titre            varchar(200),
    patient_id       uuid references patient (id),         -- null tant qu'aucune liaison patient
    etablissement_id uuid        not null references etablissement (id),
    auteur_id        uuid references professionnel_sante (id),   -- psCreateur
    cree_le          timestamptz not null,
    date_valeur      timestamptz                            -- date clinique du document
);
create index idx_document_patient on document (patient_id);

create table document_signataire (
    document_id      uuid     not null references document (id) on delete cascade,
    professionnel_id uuid     not null references professionnel_sante (id),
    rang             smallint not null,                     -- 1 = signataire principal
    constraint pk_document_signataire primary key (document_id, professionnel_id),
    constraint uq_signataire_rang unique (document_id, rang),
    constraint signataire_rang_positif check (rang >= 1)
);

create table document_destinataire (
    id               uuid primary key,
    document_id      uuid        not null references document (id) on delete cascade,
    role             varchar(20) not null,                  -- RoleDestinataire : PRINCIPAL, COPIE, EXTERIEUR, PATIENT
    rang             smallint    not null,
    professionnel_id uuid references professionnel_sante (id),
    nom              varchar(100),                          -- EXTERIEUR uniquement
    prenom           varchar(100),
    email            varchar(200),
    id_exemplaire    varchar(30),                           -- Destinataire.idExemplaire
    constraint destinataire_rang_positif check (rang >= 1),
    -- PRINCIPAL et COPIE désignent un professionnel ; EXTERIEUR (texte libre) et PATIENT (le patient du document) non
    constraint destinataire_professionnel_selon_role
        check ((role in ('EXTERIEUR', 'PATIENT')) = (professionnel_id is null)),
    constraint destinataire_exterieur_nomme check (role <> 'EXTERIEUR' or nom is not null),
    constraint uq_destinataire_rang unique (document_id, role, rang)
);
create unique index uq_destinataire_principal on document_destinataire (document_id) where role = 'PRINCIPAL';
create unique index uq_destinataire_patient on document_destinataire (document_id) where role = 'PATIENT';
-- un professionnel n'est destinataire qu'une fois : pas à la fois principal et en copie
create unique index uq_destinataire_ps on document_destinataire (document_id, professionnel_id)
    where professionnel_id is not null;
create index idx_destinataire_document on document_destinataire (document_id, role, rang);

-- ---------------------------------------------------------------------------------------------
-- Données fictives. FINESS, RPPS et ADELI commencent par 99 : ils ne coïncident avec aucun
-- identifiant réel. Les documents ne sont pas créés ici : la page hôte les crée, et les .docx
-- existants sont recensés au démarrage de l'application.
-- ---------------------------------------------------------------------------------------------
insert into etablissement (id, finess, siret, libelle, email) values
 ('e0000000-0000-0000-0000-000000000001', '990000001', '99000000100018', 'Centre hospitalier POC',
  'secretariat@ch-poc.invalid');

-- Établissement : DURAND, LEROY, PETIT (déjà cités en texte libre dans sejour.medecin_responsable).
-- Correspondants de ville : GIRARD, ROUX, FAURE.
insert into professionnel_sante (id, code, rpps, adeli, civilite, titre, nom, prenom, specialites, email, qualite, type_medecin) values
 ('a0000000-0000-0000-0000-000000000001', 'DURAND', '99000000011', '990000011', 'DOCTEUR', 'Dr', 'DURAND', 'Sophie',
  'Endocrinologie, diabétologie', 'sophie.durand@ch-poc.invalid', 'Praticien hospitalier', 'ETABLISSEMENT'),
 ('a0000000-0000-0000-0000-000000000002', 'LEROY', '99000000022', '990000022', 'DOCTEUR', 'Dr', 'LEROY', 'Marc',
  'Maladies infectieuses et tropicales', 'marc.leroy@ch-poc.invalid', 'Praticien hospitalier', 'ETABLISSEMENT'),
 ('a0000000-0000-0000-0000-000000000003', 'PETIT', '99000000033', '990000033', 'PROFESSEUR', 'Pr', 'PETIT', 'Anne',
  'Cardiologie', 'anne.petit@ch-poc.invalid', 'PU-PH', 'ETABLISSEMENT'),
 ('a0000000-0000-0000-0000-000000000004', 'GIRARD', '99000000044', '990000044', 'DOCTEUR', 'Dr', 'GIRARD', 'Paul',
  'Médecine générale', 'cabinet.girard@medecin.invalid', 'Médecin libéral', 'CORRESPONDANT'),
 ('a0000000-0000-0000-0000-000000000005', 'ROUX', '99000000055', '990000055', 'DOCTEUR', 'Dr', 'ROUX', 'Isabelle',
  'Cardiologie', 'i.roux@cardio.invalid', 'Médecin libéral', 'CORRESPONDANT'),
 ('a0000000-0000-0000-0000-000000000006', 'FAURE', '99000000066', '990000066', 'DOCTEUR', 'Dr', 'FAURE', 'Karim',
  'Endocrinologie', 'k.faure@endoc.invalid', 'Médecin libéral', 'CORRESPONDANT');

insert into adresse (id, professionnel_id, etablissement_id, type, ligne1, ligne2, ligne3, code_postal, ville, pays) values
 ('b0000000-0000-0000-0000-000000000000', null, 'e0000000-0000-0000-0000-000000000001', 'PRINCIPALE',
  '1 avenue du POC', null, null, '69000', 'Lyon', 'France'),
 ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', null, 'PROFESSIONNELLE',
  'Service d''endocrinologie', '1 avenue du POC', null, '69000', 'Lyon', 'France'),
 ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', null, 'PROFESSIONNELLE',
  'Service des maladies infectieuses', '1 avenue du POC', null, '69000', 'Lyon', 'France'),
 ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003', null, 'PROFESSIONNELLE',
  'Service de cardiologie', '1 avenue du POC', null, '69000', 'Lyon', 'France'),
 ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004', null, 'PROFESSIONNELLE',
  'Cabinet médical', '12 rue des Lilas', null, '69100', 'Villeurbanne', 'France'),
 ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', null, 'PROFESSIONNELLE',
  'Cabinet de cardiologie', '3 place Bellecour', 'Bâtiment B', '69002', 'Lyon', 'France'),
 ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000006', null, 'PROFESSIONNELLE',
  'Centre médical du Parc', '8 boulevard des Belges', null, '69006', 'Lyon', 'France');

insert into telephone (id, adresse_id, type, numero) values
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000000', 'PRINCIPAL', '04 00 00 00 00'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000000', 'FAX',       '04 00 00 00 99'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000001', 'FIXE',      '04 00 00 01 01'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000002', 'FIXE',      '04 00 00 02 02'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000003', 'FIXE',      '04 00 00 03 03'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000003', 'PORTABLE',  '06 00 00 03 03'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000004', 'FIXE',      '04 00 00 04 04'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000004', 'FAX',       '04 00 00 04 99'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000005', 'FIXE',      '04 00 00 05 05'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000006', 'FIXE',      '04 00 00 06 06');
