create table patient (
    id             uuid primary key,
    ipp            varchar(20)  not null unique,
    nom            varchar(100) not null,
    prenom         varchar(100),
    date_naissance date         not null,
    sexe           varchar(20)  not null
);

create table observation (
    id               uuid primary key,
    patient_id       uuid         not null references patient (id),
    categorie        varchar(20)  not null,
    code             varchar(30)  not null,
    libelle          varchar(200),
    valeur           varchar(50),
    unite            varchar(30),
    date_observation date         not null
);
create index idx_observation_patient_date on observation (patient_id, date_observation desc);

create table traitement (
    id          uuid primary key,
    patient_id  uuid         not null references patient (id),
    molecule    varchar(200) not null,
    posologie   varchar(200),
    date_debut  date         not null,
    date_fin    date
);
create index idx_traitement_patient on traitement (patient_id);
