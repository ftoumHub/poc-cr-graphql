-- Dossier patient étendu pour les gabarits legacy (Velocity) convertis en content controls.
-- Correspondance des chemins : docs/conception/2026-11-21-conversion-gabarits-velocity.md
--
-- Conventions inchangées : UUID, varchar validé par un enum Java, données fictives (migration V9).

-- --- Identité ------------------------------------------------------------------------------------
alter table patient
    add column nom_naissance       varchar(100),          -- pati_nom_nai ; null = nom d'usage
    add column prenoms_naissance   varchar(200),          -- INS : prénoms de naissance, séparés par des espaces
    add column lieu_naissance      varchar(5),            -- INS : code INSEE de la commune de naissance
    add column date_naissance_amo  varchar(10),           -- pati_dat_nai_lib : JJ/MM/AAAA, date lunaire possible
    add column nir                 varchar(15),           -- matricule INS (NIR ou NIA)
    add column email               varchar(200),
    add column nationalite         varchar(60),
    add column medecin_traitant_id uuid references professionnel_sante (id);

-- Le professionnel porte son sexe : accords « Cher confrère / Chère consœur » des courriers.
alter table professionnel_sante add column sexe varchar(20) not null default 'INCONNU';

-- --- Entourage : personne à prévenir, personne de confiance --------------------------------------
create table personne_entourage (
    id          uuid primary key,
    patient_id  uuid         not null references patient (id),
    role        varchar(20)  not null,            -- RoleEntourage : A_PREVENIR, CONFIANCE
    titre       varchar(30),
    nom         varchar(100) not null,
    prenom      varchar(100),
    lien        varchar(100)                      -- lien de parenté, texte libre
);
create index idx_entourage_patient on personne_entourage (patient_id, role);

-- Les adresses servent aussi au patient et à son entourage : un seul propriétaire par adresse.
alter table adresse
    add column patient_id  uuid references patient (id) on delete cascade,
    add column personne_id uuid references personne_entourage (id) on delete cascade,
    drop constraint adresse_un_seul_proprietaire,
    add constraint adresse_un_seul_proprietaire
        check (num_nonnulls(professionnel_id, etablissement_id, patient_id, personne_id) = 1);
create unique index uq_adresse_patient on adresse (patient_id, type) where patient_id is not null;
create unique index uq_adresse_personne on adresse (personne_id, type) where personne_id is not null;

-- --- Séjour : unités fonctionnelles, médecin adresseur -------------------------------------------
create table unite_fonctionnelle (
    code      varchar(10)  primary key,
    libelle   varchar(200) not null,
    telephone varchar(30),
    fax       varchar(30)
);
alter table sejour
    add column uf_medicale_code    varchar(10) references unite_fonctionnelle (code),
    add column uf_hebergement_code varchar(10) references unite_fonctionnelle (code),
    add column medecin_adresseur_id uuid references professionnel_sante (id);

-- --- Dossier clinique ----------------------------------------------------------------------------
alter table observation
    add column commentaire varchar(300),
    add column auteur      varchar(100),
    add column normalite   varchar(5);            -- HPRIM : N, L, H, LL, HH

create table allergie (
    id          uuid primary key,
    patient_id  uuid         not null references patient (id),
    categorie   varchar(100),                     -- médicamenteuse, alimentaire…
    libelle     varchar(200) not null,            -- produit, molécule
    texte       varchar(300),                     -- réaction, texte libre
    commentaire varchar(300),
    date_debut  date,
    date_fin    date                              -- renseignée = allergie levée
);
create index idx_allergie_patient on allergie (patient_id);

create table antecedent (
    id         uuid primary key,
    patient_id uuid         not null references patient (id),
    categorie  varchar(100) not null,             -- médical, chirurgical, familial
    libelle    varchar(300) not null,
    statut     varchar(50),
    date_debut date
);
create index idx_antecedent_patient on antecedent (patient_id);

create table ligne_prescription (
    id          uuid primary key,
    patient_id  uuid         not null references patient (id),
    nature      varchar(30)  not null,            -- NaturePrescription : MEDICAMENTS, PLAN_SOINS…
    statut      varchar(20)  not null,            -- StatutPrescription : VALIDEE, EN_COURS
    libelle     varchar(300) not null,
    posologie   varchar(300),
    commentaire varchar(300),
    date_debut  date         not null,
    date_fin    date
);
create index idx_prescription_patient on ligne_prescription (patient_id, nature, statut);

create table observation_medicale (
    id         uuid primary key,
    patient_id uuid         not null references patient (id),
    chapitre   varchar(100) not null,             -- MEDECINS, IDE, KINESITHERAPEUTE… ou titre libre
    ecrite_le  timestamptz  not null,
    texte      text         not null,
    auteur_id  uuid references professionnel_sante (id)
);
create index idx_obs_medicale_patient on observation_medicale (patient_id, chapitre, ecrite_le desc);

create table transmission_ciblee (
    id         uuid primary key,
    patient_id uuid         not null references patient (id),
    cible      varchar(200) not null,
    ecrite_le  timestamptz  not null,
    auteur     varchar(100),
    donnees    text,
    actions    text,
    resultats  text,
    etat       varchar(30)
);
create index idx_transmission_patient on transmission_ciblee (patient_id, ecrite_le desc);

-- --- Formulaires dynamiques ----------------------------------------------------------------------
-- Un modèle de formulaire regroupe des rubriques ; une instance (formulaire) porte leurs valeurs.
-- Un code de rubrique peut servir dans plusieurs modèles (DATESAISIE…) : le convertisseur désigne un
-- modèle de contexte par une rubrique qui lui est propre (argument « contenant »).
create table formulaire_modele (
    code    varchar(40)  primary key,             -- identifiant d'origine (« 9115405773860914995 ») ou code
    libelle varchar(200) not null
);

create table formulaire_rubrique_modele (
    modele_code varchar(40) not null references formulaire_modele (code),
    code        varchar(80) not null,
    libelle     varchar(300),
    constraint pk_rubrique_modele primary key (modele_code, code)
);
create index idx_rubrique_code on formulaire_rubrique_modele (code);

create table formulaire (
    id          uuid primary key,
    patient_id  uuid        not null references patient (id),
    modele_code varchar(40) not null references formulaire_modele (code),
    cree_le     timestamptz not null,
    modifie_le  timestamptz,
    auteur      varchar(100)
);
create index idx_formulaire_patient on formulaire (patient_id, modele_code, cree_le desc);

create table formulaire_valeur (
    formulaire_id uuid        not null references formulaire (id) on delete cascade,
    code          varchar(80) not null,
    valeur        text,
    constraint pk_formulaire_valeur primary key (formulaire_id, code)
);
