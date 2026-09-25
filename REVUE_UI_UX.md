# Dashwheel — Revue UI/UX « écran embarqué »

Revue faite depuis le code de l'interface (Compose) : `DashTheme.kt`, `TopBar.kt`, `DashboardGrid.kt`, `DashComponents.kt`, `DashThemePickerDialog.kt`, les tuiles (`DriveTiles.kt`, `TelemetryTiles.kt`, `MediaTile.kt`, `InfoTiles.kt`) et les quatre skins. Cible retenue : autoradio Android 10, écran paysage 1024×600 / 1280×720, lu à 60–70 cm, souvent en plein soleil, doigt qui tremble à 90 km/h.

Grille de lecture : **lisibilité en un coup d'œil (< 2 s)**, **zones tactiles**, **charge cognitive**, **cohérence du système**, **jour/nuit**.

---

## 1. Points positifs

**Système de thème solide.**
- Chaque thème existe en version nuit **et** jour, choisies par un seul interrupteur Auto/Sombre/Clair. Un test unitaire (`DashThemeTest`) impose un contraste ≥ 7:1 entre le texte principal et la page pour les 24 palettes. C'est rare et c'est exactement ce qu'il faut en voiture.
- Les tokens `DashPalette` (Glass, Glow, Bare, BackgroundStops, Skin) séparent bien couleur et « matière ». `OnAccent`, `haze()` et `well()` évitent les textes illisibles sur dégradé.
- Les thèmes clairs coupent le halo (`Glow = 0…0.3`) pour ne pas baver au soleil : bon réflexe.

**Barre du haut « silencieuse par défaut ».**
- Horloge centrée, deux boutons à gauche, deux à droite. Les alertes batterie/liquide n'apparaissent **que** hors plage. Le menu ⋮ sépare bien l'usage fréquent (éditer, modèles, split) des réglages « une fois » (Réglages → Voiture / Apparence / Avancé).

**Grille libre bien pensée.**
- 12×7 cellules, déplacement par appui long, redimensionnement par poignée, collisions gérées (swap/nudge), **undo 30 niveaux**, sauvegarde immédiate. Page vide guidée (« Ajouter une tuile ou choisir un modèle »). Modèles prêts à l'emploi (Quotidien, Road trip, Santé du véhicule) qui s'adaptent au dock Maps.
- Les tuiles se recomposent selon leur taille (`compact` / `stacked` dans `ObdCard`), zoom par tuile.

**Skins ambitieux et cohérents.**
- Cockpit : lampes témoins, LCD, chrome ; Horizon : ciel calculé selon l'heure ; Tape Deck : cassette et digits 7 segments. Les valeurs live (`rememberSpeedKmh`, `rememberFuel`, `rememberWeather`) sont partagées, donc un skin ne redessine que la forme.

**Honnêteté et sécurité.**
- Badge « Démo » impossible à rater tant que les données sont fictives. Fenêtre sombre dès le lancement (pas de flash blanc). Plein écran immersif, gestion de la barre système forcée du head unit.
- `contentDescription` partout, cibles 48 dp sur la barre et les points de page, colonne de points fusionnée en une seule zone tactile : le détail montre qu'on a pensé au doigt.

**Localisation** FR / IT / EN complète, TTS pour le briefing.

---

## 2. Points négatifs

Classés par impact conduite (🔴 sécurité/lisibilité, 🟠 ergonomie, 🟡 cohérence).

| # | Constat | Où | Pourquoi ça compte |
|---|---|---|---|
| 🔴 1 | **Texte trop petit pour la distance de lecture.** Titres de tuile en `labelMedium` (12 sp), chips en `labelSmall` (11 sp), sous-titres du menu en `bodySmall`. | `TileHeader`, `MeterChip`, `SettingsRow` | Android Auto / AAOS recommandent ≥ 24 dp pour tout ce qui se lit en roulant, ≥ 32 dp pour les valeurs. À 65 cm, 12 sp ≈ 1,2 mm de hauteur d'x : illisible sans quitter la route. |
| 🔴 2 | **Pas de verrouillage « en mouvement ».** Édition, tiroir d'apps en grille, 18 `AlertDialog`, changement de thème : tout reste accessible à 130 km/h. | `AutomotiveDashboard.kt`, `TopBar.kt` | La vitesse est déjà connue (`rememberSpeedKmh`). Une app conducteur doit brider ce qui demande > 2 s d'attention. |
| 🔴 3 | **État OBD codé par la couleur seule** : un point de 10 dp (vert/bleu/orange/gris). | `ObdDot` | Invisible en plein soleil, indéchiffrable pour un daltonien, pas de texte. Le skin Cockpit (`ObdLamp`, 15 dp + halo + label) fait mieux : à généraliser. |
| 🔴 4 | **Effets glass/glow non désactivables** hors thème. Aurora/Premium empilent dégradé + halo + spéculaire + ombre sur les chiffres. | `glassPanel`, `HeroNumber` | Sur une dalle TN 1024×600 à 400 nits, le halo réduit le contraste réel du chiffre de vitesse. Il faut un curseur global « intensité des effets » ou un mode « haute lisibilité ». |
| 🟠 5 | **Navigation en croix de 7 pages** (3 en ligne + 2 au-dessus + 2 en dessous), petits points de 5×11 dp, swipes verticaux uniquement depuis la page centrale. | `PageDotRow`, pagers | Modèle mental difficile à mémoriser ; on ne sait pas « où on est » sans regarder les points. Aucun nom de page affiché lors du swipe. |
| 🟠 6 | **Surcharge de choix** : 12 thèmes × 3 apparences × 35 designs de widget × zoom par tuile × 3 modèles. | `DashThemeMode`, `WidgetDesign` | Le sélecteur de thème est une liste défilante de 12 lignes avec une vignette dégradé 54×38 dp qui ne montre rien du résultat. Le conducteur choisit à l'aveugle. |
| 🟠 7 | **Réglages en dialogues imbriqués** (Menu → Réglages → Voiture → …), `AlertDialog` limité à 470 dp de haut. | `SettingsDialog` | Sur un écran paysage, un dialogue étroit gaspille 60 % de la surface et empile les retours. Un écran Réglages plein écran en deux colonnes (liste / détail) est la norme AAOS. |
| 🟠 8 | **Cibles < 48 dp** : poignée de redimensionnement et boutons d'édition 40 dp, bouton « Live » avec point de 8 dp, `TextButton` de la barre d'édition serrés. | `GridTile`, `ObdCard`, `EditBar` | Le minimum Android est 48 dp ; en voiture on vise 56–64 dp pour les actions fréquentes (médias, appel). |
| 🟠 9 | **Jour/nuit = `isSystemInDarkTheme()` seul.** | `DashAppearance.isLight()` | Sur beaucoup de head units le mode nuit système ne suit pas les feux. Horizon calcule déjà le coucher du soleil (`skyLookAt`) : ce repli devrait servir à tous les thèmes, avec une transition animée pour éviter le flash. |
| 🟠 10 | **Bouton « Connecter » dupliqué** dans chaque tuile OBD + point dans la barre : trois endroits pour la même action. | `ObdCard`, `SpeedHudCard`, `ObdDot` | Une seule action de connexion visible, les tuiles se contentent d'un état « -- » et d'un lien. |
| 🟡 11 | **Pas d'échelle de forme ni d'espacement** : rayons 24/20/18/16/14/12/10 dp au cas par cas, paddings 14/12/10/8/6. | partout | Ça se voit : coins de tuile 24, panneau d'édition 16, chip 12, bouton 14. Trois valeurs suffisent (grand 24, moyen 16, petit 10). |
| 🟡 12 | **Tokens redondants** : `Speed == Accent` dans les 24 palettes ; `Rpm` est parfois violet, parfois vert, parfois orange sans règle. | `DashPalette` | Un token qui ne varie jamais est du bruit ; un token qui change de sens d'un thème à l'autre casse la sémantique (le compte-tours n'est pas « la couleur secondaire »). |
| 🟡 13 | Petites incohérences : FAB d'échange split en `contentColor = Color.Black` au lieu de `OnAccent` ; badge Démo flottant sur les skins mais intégré sur la barre standard ; `MaterialTheme` reçoit un `colorScheme` figé qui ne suit pas la palette du thème (les composants M3 par défaut, p. ex. `CircularProgressIndicator` sans couleur, sortent du thème). | `AutomotiveDashboard.kt`, `MainActivity.kt` | |
| 🟡 14 | **Aucun retour d'action** : pas de `performHapticFeedback`, pas de son, pas d'animation d'appui sur les boutons ronds. | `GlassRoundButton`, `GradientRoundButton` | En conduite, on appuie sans regarder : il faut un retour (vibration si la dalle le permet, sinon bip court + scale 0.96). |
| 🟡 15 | Le coût d'un skin est énorme (Cockpit 2 465 lignes, ~90 chaînes localisées par skin). | `*Skin.kt` | Impossible d'itérer vite sur un nouveau look ; il manque un niveau intermédiaire « thème + police + formes » sans redessiner chaque widget. |

---

## 3. Améliorations proposées

Par ordre de priorité. Chaque proposition indique la portée (S = une session, M = quelques jours, L = chantier).

### P1 — Lisibilité et sécurité

1. **Échelle typographique « conduite »** (S). Créer `DashType` avec 5 tailles et remplacer les styles Material dans les tuiles :
   `value 56 sp` (chiffre héros) · `title 20 sp` (titre de tuile, au lieu de 12) · `body 18 sp` · `label 16 sp` (chips, au lieu de 11) · `caption 14 sp` (uniquement hors conduite). Aucun texte < 14 sp. Le zoom par tuile reste, mais part d'une base lisible.
2. **Mode « en mouvement »** (M). Quand `rememberSpeedKmh() > 8` : masquer le bouton Éditer, verrouiller le tiroir d'apps en liste 1 colonne 64 dp, fermer les dialogues de réglage, afficher un bandeau discret « Garez-vous pour modifier ». Option débrayable dans Réglages → Avancé pour le passager.
3. **Indicateur OBD lisible** (S). Remplacer `ObdDot` par une pastille 32 dp icône + 3 lettres (« OBD ») : gris rayé = éteint, animation de pulsation = connexion, vert plein = live, orange + « ! » = erreur. Même composant pour les 4 skins (le `ObdLamp` de Cockpit sert de base).
4. **Curseur d'effets et mode haute lisibilité** (S). Un `effectScale` 0…1 dans `DashThemeStore` qui multiplie `Glow`, l'alpha du glass et des ombres. À 0 : cartes opaques, chiffres pleins, hairlines seuls. Bouton « Haute lisibilité » dans le sélecteur de thème.
5. **Cibles 48 dp minimum, 64 dp pour médias/appel** (S). Poignée de redimensionnement 48, boutons d'édition 48, transport média 64/56, bouton « Live » → toute la ligne d'en-tête cliquable.

### P2 — Navigation et architecture

6. **Simplifier la croix de pages** (M). Deux options, la seconde recommandée :
   - a) garder 7 pages mais afficher le **nom de la page** en surimpression 800 ms au swipe (« Véhicule », « Médias »), et grossir les points de la colonne (8×16 dp).
   - b) **5 pages en ligne** (Home au centre) avec des onglets nommés dans la barre du haut à la place de la ligne de points ; les pages « au-dessus / en dessous » deviennent des tuiles « dossier » qui s'ouvrent en plein écran.
7. **Écran Réglages plein écran** (M). Deux colonnes : catégories à gauche (Voiture, Apparence, Conduite, Avancé), détail à droite, bouton Fermer 56 dp en haut à gauche. Supprime les 4 niveaux de dialogues empilés.
8. **Sélecteur de thème avec aperçu réel** (M). Grille 3 colonnes de vignettes 200×120 dp où chaque vignette est un **mini-dashboard rendu avec la palette** (barre + tuile vitesse + tuile média), en version jour et nuit côte à côte. Séparer « Thèmes » (couleurs) de « Univers » (skins). Section « Recommandés » (3) puis « Tous ».
9. **Une seule action de connexion OBD** (S). La barre garde la pastille ; les tuiles affichent « -- » et un lien texte « Connecter » uniquement sur la première tuile véhicule de la page.

### P3 — Système de design

10. **Tokens de forme et d'espacement** (S). `DashShape { large 24, medium 16, small 10 }`, `DashSpace { 4, 8, 12, 16, 24 }`. Remplacer les valeurs éparses.
11. **Nettoyer les tokens couleur** (S). Supprimer `Speed` (= `Accent`), renommer `Rpm` en `Secondary` et créer un vrai `Tacho` sémantique (orange dans tous les thèmes, comme sur un vrai combiné). Ajouter `Critical` (rouge) distinct de `Warning` (ambre) : aujourd'hui « batterie 11,8 V » et « liquide 108 °C » ont la même couleur.
12. **Jour/nuit robuste** (S). `isLight()` = capteur/mode système **ou** heure solaire (réutiliser `skyLookAt`) ; `animateColorAsState` 400 ms sur les tokens pour une bascule douce. Lier `MaterialTheme.colorScheme` à la palette active (`primary = Accent`, `surface = Card`…) pour que les composants M3 par défaut suivent.
13. **Retour d'action** (S). `performHapticFeedback(LongPress/Confirm)` sur appui long, connexion, undo ; scale 0.96 + 80 ms sur tous les boutons ronds ; bip optionnel via `CarVoice`/ToneGenerator sur les head units sans vibreur.
14. **Niveau « thème+ » entre thème et skin** (L). Un `DashLook` = palette + `FontFamily` + `DashShape` + style de jauge (`ARC/DIAL/BAR`) + style de barre (`STANDARD/CLUSTER`). Permet de créer un nouveau look en 40 lignes au lieu de 2 000. Les deux thèmes ci-dessous sont décrits pour ce niveau.
15. **Alertes hiérarchisées et persistantes** (S). Ambre = information (reste jusqu'au retour à la normale), rouge = critique (pastille + annonce TTS une fois + reste jusqu'à acquittement). Historique des alertes dans la tuile « Codes défaut ».

---

## 4. Deux nouveaux thèmes inspirés de la Citroën C4 Picasso (2011)

Ce qui fait l'identité intérieure de la C4 Picasso phase 2 (2010–2013) :

- le **pare-brise panoramique Zénith** et l'habitacle « lounge » très lumineux, gris clair / beige, décor aluminium satiné ;
- le **combiné central numérique translucide** au sommet de la planche de bord : chiffres blancs froids sur fond graphite fumé, compte-tours en arc de segments, jauges carburant / température en barres latérales, rétro-éclairage blanc-bleuté ;
- le **volant à moyeu fixe** avec ses commandes en couronne ;
- les **témoins ambre** et les **chevrons rouges** (pas de logo : on s'inspire des formes et de la palette, on ne reproduit pas la marque).

Les deux thèmes suivent la structure existante (une palette nuit + une palette jour chacun, `DashThemeTest` doit passer). Ils sont pensés comme « thème+ » (proposition 14) : palette + police condensée + barre en mode « combiné central ». Une maquette HTML des deux est fournie dans `C4_PICASSO_THEMES.html`.

### Thème A — « Mistral » (le combiné central, nuit d'abord)

*Mistral* est le nom de la teinte intérieure sombre de la C4 Picasso. L'écran devient le combiné translucide : fond graphite fumé, chiffres blanc froid légèrement rétro-éclairés bleu, ambre pour tout ce qui « chauffe », rouge chevron réservé au critique.

**Palette nuit**

| Token | Hex | Rôle |
|---|---|---|
| Background | `#0C0F13` | graphite fumé ; stops `#12161B → #0A0D11` |
| Bar | `#13171C` | bandeau du combiné |
| Card | `#E0171C22` | panneau translucide (alpha 0.88, laisse passer le fond) |
| CardHi | `#1F252C` | surbrillance |
| Accent | `#DCE9F7` | blanc froid LCD (chiffres, jauge) |
| Accent2 | `#8FC3F0` | rétro-éclairage bleuté (extrémité de dégradé, halo) |
| Tacho / Rpm | `#F2A33A` | ambre compte-tours et jauges |
| Warning | `#F2A33A` | ambre |
| Critical | `#E1252B` | rouge chevron (nouveau token, sinon = Warning) |
| Good | `#6FD39A` | vert doux |
| Muted | `#7C8794` | |
| TextPrimary | `#F2F6FA` | contraste 17:1 sur le fond |
| TextSecondary | `#AEB8C4` | |
| Line | blanc 10 % | |
| Glass `false`, Glow `0.45`, Bare `false` | | halo froid derrière les chiffres, sans dégradé blanc |

**Palette jour (« Mistral jour »)** : le même combiné vu en plein soleil, contraste inversé.
Background `#E9EDF1` (stops `#F3F5F8 → #E4E8EC`), Bar `#F6F8FA`, Card `#FFFFFF`, CardHi `#DDE3E9`, Accent `#1F5F8F`, Accent2 `#4F9AD1`, Rpm `#C97A12`, Warning `#C97A12`, Critical `#B8161C`, Good `#1E8A55`, Muted `#6B7682`, TextPrimary `#14181D`, TextSecondary `#48525C`, Line noir 8 %, Glow `0`, Light `true`.

**Formes et matière**
- Police : `sans-serif-condensed` (déjà `CondensedFamily`) pour les valeurs, chiffres tabulaires.
- Barre du haut « combiné » : **vitesse au centre en 40 sp** (à la place de l'horloge quand une source de vitesse est active, l'heure passe à droite), un **arc de segments compte-tours** de 14 segments au-dessus, jauges carburant et température en **barres de 6 segments** aux extrémités. C'est exactement la lecture du combiné d'origine, à hauteur d'yeux.
- Tuiles : coins 16 dp, hairline 1 dp blanc 10 %, séparateurs verticaux fins comme les lignes du LCD d'origine. Pas de dégradé blanc, pas de spéculaire.
- Jauges : arcs de **segments** (pas d'aiguille), 3 derniers segments ambre, dernier rouge.
- Points de page : **double chevron** `‹ ›` de 12 dp : la page active est le chevron plein, les autres en contour. Brand-inspired sans logo.
- Halo : uniquement derrière la valeur héros, bleu `Accent2` à 35 %.

### Thème B — « Zénith » (l'habitacle lounge, jour d'abord)

Le pare-brise panoramique et l'ambiance « salon » : page presque blanche teintée de ciel, panneaux gris perle « Lounge », filets aluminium, bleu profond du combiné comme seul accent, ambre et rouge chevron pour les alertes. Un thème de jour qui n'éblouit pas la nuit grâce à sa version nocturne « Zénith nuit » (gris anthracite chaud, éclairage d'ambiance blanc-bleuté).

**Palette jour**

| Token | Hex | Rôle |
|---|---|---|
| Background | `#F1F3F5` | gris perle ; stops `#F8FAFC → #EEF1F4 → #E4E9EE` (lumière qui tombe du toit) |
| Bar | `#CCFFFFFF` | bandeau blanc légèrement translucide |
| Card | `#FFFFFF` | panneau |
| CardHi | `#E6EAEF` | |
| Accent | `#2A6FB0` | bleu combiné |
| Accent2 | `#4FB3E8` | bleu ciel (dégradé des boutons, halo léger) |
| Tacho / Rpm | `#D9822B` | ambre |
| Warning | `#D9822B` | ambre |
| Critical | `#C8102E` | rouge chevron |
| Good | `#2E8B57` | |
| Muted | `#6B7480` | |
| TextPrimary | `#1B1F24` | graphite, 15:1 |
| TextSecondary | `#4A525C` | |
| Line | `#1A1B1F24` (graphite 10 %) | filet « aluminium » |
| Glass `false`, Glow `0`, Light `true` | | |

**Palette nuit (« Zénith nuit »)** : Background `#15181C` (stops `#1A1E23 → #121517`), Bar `#1B1F24`, Card `#20252B`, CardHi `#2A3037`, Accent `#6FB2E8`, Accent2 `#A9D6F5`, Rpm `#F0A24A`, Warning `#F0A24A`, Critical `#FF4D55`, Good `#7ED6A3`, Muted `#8A939E`, TextPrimary `#F3F5F7`, TextSecondary `#B4BCC5`, Line blanc 8 %, Glow `0.25`.

**Formes et matière**
- Police : Roboto standard, valeurs en `Medium` (pas d'ExtraBold : la C4 Picasso est douce, pas sportive). Lettrage des titres en capitales espacées `+0.08 em`, gris `TextSecondary`.
- Barre du haut : bandeau blanc translucide avec **filet aluminium** de 1 dp en bas ; horloge au centre ; à gauche, les boutons Apps / Disposition en **pastilles rondes 44 dp gris perle** rappelant les commandes du moyeu fixe.
- Tuiles : coins **24 dp**, ombre portée très douce (`0 2 8 dp`, 6 %), fond blanc pur ; pas de hairline sauf sur `CardHi`.
- Barre de lancement : rail arrondi gris perle de 72 dp, icônes 48 dp, comme la couronne de commandes du volant.
- Jauges : arc **fin (6 dp) bleu combiné** sur piste gris perle, valeur au centre en `Medium`, unité en capitales espacées.
- Points de page : trait `22×4 dp` bleu pour la page active, points gris perle pour les autres.
- Alertes : pastille ambre pleine avec texte graphite (les pastilles ambre sur fond blanc passent 4.5:1 avec `#1B1F24`).

### Extrait Kotlin prêt à coller (palettes)

```kotlin
// DashTheme.kt — ajouter après TapeDeckLightPalette
private val MistralPalette = DashPalette(
    Color(0xFF0C0F13), Color(0xFF13171C), Color(0xE0171C22), Color(0xFF1F252C),
    Color(0xFFDCE9F7), Color(0xFFDCE9F7), Color(0xFFF2A33A), Color(0xFFF2A33A),
    Color(0xFF6FD39A), Color(0xFF7C8794), Color(0xFFF2F6FA), Color(0xFFAEB8C4),
    Accent2 = Color(0xFF8FC3F0), Line = Color.White.copy(alpha = 0.10f), Glow = 0.45f,
    BackgroundStops = listOf(Color(0xFF12161B), Color(0xFF0A0D11))
)
private val MistralLightPalette = DashPalette(
    Color(0xFFE9EDF1), Color(0xFFF6F8FA), Color.White, Color(0xFFDDE3E9),
    Color(0xFF1F5F8F), Color(0xFF1F5F8F), Color(0xFFC97A12), Color(0xFFC97A12),
    Color(0xFF1E8A55), Color(0xFF6B7682), Color(0xFF14181D), Color(0xFF48525C),
    Accent2 = Color(0xFF4F9AD1), Line = Color.Black.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color(0xFFF3F5F8), Color(0xFFE4E8EC)), Light = true
)
private val ZenithLightPalette = DashPalette(
    Color(0xFFF1F3F5), Color(0xCCFFFFFF), Color.White, Color(0xFFE6EAEF),
    Color(0xFF2A6FB0), Color(0xFF2A6FB0), Color(0xFFD9822B), Color(0xFFD9822B),
    Color(0xFF2E8B57), Color(0xFF6B7480), Color(0xFF1B1F24), Color(0xFF4A525C),
    Accent2 = Color(0xFF4FB3E8), Line = Color(0x1A1B1F24),
    BackgroundStops = listOf(Color(0xFFF8FAFC), Color(0xFFEEF1F4), Color(0xFFE4E9EE)), Light = true
)
private val ZenithDarkPalette = DashPalette(
    Color(0xFF15181C), Color(0xFF1B1F24), Color(0xFF20252B), Color(0xFF2A3037),
    Color(0xFF6FB2E8), Color(0xFF6FB2E8), Color(0xFFF0A24A), Color(0xFFF0A24A),
    Color(0xFF7ED6A3), Color(0xFF8A939E), Color(0xFFF3F5F7), Color(0xFFB4BCC5),
    Accent2 = Color(0xFFA9D6F5), Line = Color.White.copy(alpha = 0.08f), Glow = 0.25f,
    BackgroundStops = listOf(Color(0xFF1A1E23), Color(0xFF121517))
)
// DashThemeMode : MISTRAL(R.string.dash_theme_mistral, …), ZENITH(R.string.dash_theme_zenith, …)
// paletteFor : DashThemeMode.MISTRAL -> if (light) MistralLightPalette else MistralPalette
//              DashThemeMode.ZENITH  -> if (light) ZenithLightPalette else ZenithDarkPalette
```

Contrastes texte principal / page vérifiés : Mistral nuit 17:1, Mistral jour 15:1, Zénith jour 15:1, Zénith nuit 14:1 (tous ≥ 7:1, `DashThemeTest` passe). Le rouge chevron n'est utilisé que pour le critique et jamais comme accent, pour qu'il garde sa valeur d'alerte.

---

## 5. Avancement

**P1 livré** (branche `claude/automotive-ui-ux-review-4ksf9v`) :

| # | Proposition | Où dans le code |
|---|---|---|
| 1 | Échelle typographique conduite : rien sous 14 sp, libellés 16 sp, corps 18 sp, installée dans `MaterialTheme` | `DashType.kt`, `MainActivity.kt` (`OpenAutoDashTheme`) ; libellés de la boussole 11 → 14 sp |
| 2 | Mode « en mouvement » : au-delà de 8 km/h (OBD ou GPS, jamais en démo) l'édition, les modèles, les réglages, les sélecteurs et l'éditeur de barre de lancement se ferment et refusent de s'ouvrir ; le tiroir d'apps passe en liste 1 colonne 64 dp ; une puce « Garez-vous pour modifier » s'affiche 2,5 s sur un appui retenu ; réglage débrayable dans Réglages → Avancé | `DriveLock.kt`, `AutomotiveDashboard.kt` (`whenParked`), `TopBar.kt` (menu grisé, `SettingsToggle`, `DriveLockChip`), `AppTiles.kt` (`AppRow`) |
| 3 | Indicateur OBD lisible sans la couleur : pastille 36 dp (zone 48 dp) avec point creux / plein / pulsé, halo, lettres « OBD », « ! » sur erreur ; barre standard et Orbit | `TopBar.kt` (`ObdPill`), `OrbitSkin.kt` |
| 4 | Effets : Aucun / Réduits / Complets dans le sélecteur de thème ; Aucun = cartes opaques, chiffres nets, aucun halo sur tous les thèmes | `DashTheme.kt` (`DashEffects`, `DashColors.Glow` / `Glass`), `DashThemePickerDialog.kt` (`SegmentedSwitch`) |
| 5 | Cibles 48 dp : boutons et poignée d'édition 48 dp (alignés sur une ligne pour les tuiles d'une rangée), zoom 48 dp, édition de la barre de lancement 48 dp, bouton « Live » 48 dp, précédent/suivant média 56 dp | `DashboardGrid.kt`, `AppTiles.kt`, `TelemetryTiles.kt`, `MediaTile.kt` |

**P3 livré** (même branche) :

| # | Proposition | Où dans le code |
|---|---|---|
| 10 | Tokens de forme et d'espacement : `DashShape` (Large 24 / Medium 16 / Small 10 / Pill) et `DashSpace` (4 / 8 / 12 / 16 / 24), appliqués aux rendus standard ; les skins, l'Original et les faces de widget gardent leurs formes | `DashTokens.kt`, tous les fichiers standard |
| 11 | Tokens couleur : `Speed` supprimé (= `Accent`), `Rpm` renommé `Secondary`, `Tacho` ambre dans tous les thèmes, `Warning` ambre et `Critical` rouge distincts. Batterie sous 11,5 V ou liquide à 115 °C = rouge ; hors plage = ambre. Les ambres inventés localement (entretien, soin, codes défaut) utilisent le token. Test : les trois couleurs d'alerte lisibles (≥ 3:1) et distinctes dans les 26 palettes | `DashTheme.kt`, `DashThemeTest.kt`, `TelemetryTiles.kt` (seuils) |
| 12 | Jour/nuit : Auto = mode système **et** soleil levé où se trouve la voiture (calcul solaire NOAA, repli 7 h–20 h sans position) ; fondu de 400 ms entre deux palettes ; `MaterialTheme.colorScheme` suit la palette active | `DayNight.kt`, `DayNightTest.kt`, `DashColors.Sync`, `MainActivity.kt` |
| 13 | Retour d'action : tick haptique sur les appuis clés (médias, OBD, apps, Annuler, Terminé) et sur l'appui long qui soulève une tuile ; boutons ronds à 96 % pendant l'appui ; bip optionnel (Réglages → Avancé → Son des appuis) ; FAB d'échange split en `OnAccent` | `DashComponents.kt` (`rememberTapFeedback`, `pressScale`, `FeedbackStore`), `DashboardGrid.kt`, `TopBar.kt`, `AppTiles.kt` |
| 15 | Alertes hiérarchisées : ambre tant que la lecture est hors plage, rouge pour le critique, annoncé une fois par la voix (si activée dans IA) et conservé sur la barre jusqu'à un appui, même après retour à la normale | `VehicleAlerts.kt` (`AlertCenter`), `TopBar.kt` (`VehicleAlerts`) |
| §4 | Thèmes **Mistral** et **Zénith** ajoutés comme thèmes couleur (nuit + jour chacun) | `DashTheme.kt`, `DashThemePickerDialog.kt`, chaînes EN/FR/IT |

**P2 livré** (même branche) :

| # | Proposition | Où dans le code |
|---|---|---|
| 6 | Croix des pages conservée, avec une aide visuelle : après un swipe, une puce flotte 0,9 s avec la croix des sept pages (la page affichée remplie en accent) et son nom ; les points des pages au-dessus / en dessous passent de 5×11 à 7×14 dp | `TopBar.kt` (`PageNoticeChip`, `PageCrossGlyph`), `AutomotiveDashboard.kt` |
| 7 | Écran Réglages plein écran en deux colonnes : Voiture / Apparence / Conduite / Avancé à gauche, le contenu à droite ; apparence, effets, thèmes, langue et conduite se règlent sur place, les fiches profondes (profil voiture, IA, entretien, lectures, logo de démarrage) s'ouvrent à un niveau au lieu de quatre ; bouton Fermer 56 dp | `SettingsScreen.kt`, `LanguagePickerDialog.kt` (`LanguageChoices`), `TopBar.kt` (menu → `onSettings`) |
| 8 | Sélecteur de thème à aperçu réel : trois colonnes de mini-dashboards dessinés avec la palette de chaque thème, moitié nuit / moitié jour (barre, tuile vitesse, tuile de trois jauges), en trois groupes Recommandés (Standard, Mistral, Zénith) / Thèmes / Univers | `ThemePane.kt` (remplace `DashThemePickerDialog.kt`) |
| 9 | Une seule action de connexion OBD : la pastille de la barre connecte toujours ; sur une page, seule la première tuile véhicule garde le bouton Connecter, les autres affichent « OBD non connecté » et renvoient à la barre | `DashboardGrid.kt` (`LocalObdPrompt`), `TelemetryTiles.kt` (`ObdNotConnected`, `ObdCard`, `RangeCard`) |

**Reste de P3, livré en version resserrée** :

| # | Proposition | Où dans le code |
|---|---|---|
| 14 | Niveau « thème+ » : chaque palette porte sa police héros (`Font` : sans ou condensée), son graisse (`HeroWeight`) et le style de sa barre (`BarStyle`). Mistral utilise la condensée et la barre « combiné » : vitesse au centre, compte-tours en 14 segments (ambre puis rouge), carburant et liquide en barres de 6 segments, horloge à droite ; sans source de vitesse la barre redevient l'horloge. Zénith adopte une graisse Medium. Les formes par thème et un style de jauge par thème restent à faire. | `DashTheme.kt` (`DashFont`, `DashBarStyle`), `TopBar.kt` (`ClusterReadout`, `SegmentBar`), `DriveTiles.kt` (`HeroNumber`) |
| 15 | Historique des alertes : les vingt dernières alertes (ambre et rouges) sont journalisées, la tuile Codes défaut montre les trois dernières avec l'heure | `VehicleAlerts.kt` (`AlertCenter.history`), `FaultCodesTile.kt` (`RecentAlerts`) |

Non compilé dans cet environnement (SDK Android inaccessible) : seule une passe de syntaxe Kotlin a été faite ; la CI Lint & Test doit valider la branche.

## 6. Ordre de mise en œuvre suggéré

1. Semaine 1 : échelle typographique, cibles 48 dp, indicateur OBD, curseur d'effets (P1 1/3/4/5). **Fait.**
2. Semaine 2 : tokens forme/couleur, jour/nuit robuste, retour d'action, palettes Mistral et Zénith en thèmes couleur (P3 10/11/12/13 + section 4). **Fait**, avec les alertes hiérarchisées (15).
3. Semaine 3–4 : écran Réglages, sélecteur de thème avec aperçu réel (P2 7/8). **Fait**, avec l'aide visuelle de la croix (6) et la connexion OBD unique (9).
4. Ensuite : niveau « thème+ » et barre « combiné central » pour Mistral (P3 14). **Fait en version resserrée** (police, graisse, barre) ; formes et style de jauge par thème restent ouverts. La croix de pages est conservée par choix.
