# Kilométrage CSSPI

Application Android qui note automatiquement les déplacements entre les
établissements pendant les heures de travail, puis remplit le formulaire de
réclamation de kilométrage (.xlsx).

## Installer

1. Télécharger le fichier `.apk` de la dernière version dans
   [Releases](../../releases/latest).
2. L'ouvrir sur le téléphone et autoriser l'installation d'applications de
   cette source si Android le demande.
3. Au premier lancement, entrer son nom, son numéro d'employé et son adresse :
   ils servent seulement à remplir l'en-tête du formulaire et restent sur le
   téléphone.
4. Accorder la localisation « Toujours autoriser » et les notifications, sinon
   les trajets ne sont pas détectés.

Les mises à jour s'installent par-dessus la version précédente sans perdre les
trajets ni les lieux.

## Fonctionnement

- Les 128 lieux du centre de services sont déjà chargés, avec leur zone.
- Un arrêt de plus de 2 min 30 dans une zone compte comme une visite ; le
  trajet entre deux visites est enregistré avec la distance de la grille
  officielle.
- Le domicile sert de point de passage : il n'est jamais inscrit au formulaire.
- La détection ne fonctionne que pendant la plage de travail (Réglages), en
  semaine, hors congés.
- L'onglet Trajets exporte le formulaire rempli, 25 lignes par fichier.

Les trajets, les lieux et le profil restent sur le téléphone. La carte
(OpenStreetMap), le contour des bâtiments (Overpass) et la recherche d'adresse
(géocodeur d'Android) passent par Internet.

## Compiler

```
./gradlew :app:assembleDebug
```

Voir `CLAUDE.md` et `HANDOFF.md` pour le code et l'état des travaux.
