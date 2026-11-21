La vidéo est prête : docs/videos/3-gabarit-legacy-converti.webm (environ 2 min 40, 16 Mo). Elle montre le gabarit converti le plus complexe, le 07 (dossier de séjour SSR), passer des repères aux données de Jeanne DUBOIS dans le vrai OnlyOffice.

Déroulé :
1. On ouvre le gabarit GAB-07 : chaque donnée est un repère «Libellé», et les boucles s'affichent en clair, par exemple « pour chaque Allergies : si Fin vide… ».
2. Depuis la page hôte, on l'instancie pour Jeanne DUBOIS (née LEMAIRE, 84 ans, SSR).
3. On parcourt quatre sections avant le rafraîchissement : repères partout.
4. « Rafraîchir les données » : 780 liaisons mises à jour, en une requête et en moins de 2 secondes.
5. On revient sur les mêmes sections : les données ont remplacé les repères.

Chargement : le document s'ouvre sans erreur et passe de 37 à 45 pages une fois rempli. Les 780 contrôles sont mis à jour et OnlyOffice enregistre le document.

Cohérence du cas de test, telle que la vidéo la montre :
- Personne à prévenir : sa fille, Christine MARCHAND, avec adresse et téléphone.
- Allergies : pénicillines et kiwi ; l'intolérance à la codéine, levée, n'apparaît pas.
- Antécédents : cohérents avec le motif du séjour (prothèse de hanche, hypertension, diabète, fibrillation atriale).
- Carnet de vaccination : Revaxis puis Boostrixtetra pour le tétanos, avec un rappel prévu en 2033 ; Influvac et Vaxigrip pour la grippe ; Prevenar pour le pneumocoque.
- Observations IDE : les 5 dernières, datées et signées par l'infirmière, Mme MOREL.
- Constantes : un tableau, une ligne par mesure (pression, saturation, température, poids).
- Transmissions ciblées : cible, données, actions, résultats, datées « 20/11/2026 07:30 ».

Défauts corrigés en préparant la vidéo :
- Carnet de vaccination : les vaccins étaient tirés au hasard, par exemple un vaccin antigrippal sous la rubrique tétanos. J'ai corrigé le générateur de V9, puis les valeurs dans ta base locale, sans rien recréer.
- Constantes : elles s'entassaient dans une seule ligne, parce que la boucle d'origine répète une ligne de tableau. Le convertisseur les transforme maintenant en vrai tableau. Il reste une seule structure de ce genre non convertie, dans un autre gabarit.
- Dates-heures : elles s'affichaient « 2026-11-20 07:30 ». Le plugin les formate désormais en « 20/11/2026 07:30 ».

Les tests JS et Python repassent. Les gabarits convertis, les rendus PDF et les documents GAB- / CR- de l'application ont été régénérés.

Pour la refaire : node scenario-gabarit-converti.js --video dans outils/videos (ou npm run legacy). Elle tourne sur l'instance principale (port 8081) parce que GAB-07 y est importé. Chaque passage crée une copie CR-07-DUBOIS-FILM-<heure> dans la liste des documents ; il y en a quelques-unes de mes essais, à supprimer si elles te gênent.