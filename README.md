# RAG MCP Studio

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![ONNX](https://img.shields.io/badge/Embeddings-ONNX_Embarqu%C3%A9-teal.svg)](https://onnxruntime.ai/)
[![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-purple.svg)](https://modelcontextprotocol.io/)

**RAG MCP Studio** est une application **100 % autonome** en Java 21 permettant de **vectoriser localement** vos documents dans une base **SQLite portable (`.db`)** et de l'exposer via un **serveur MCP (Model Context Protocol)** en Streamable HTTP, directement exploitable par **OpenCode** (ou tout autre client MCP compatible).

> **Principe fondamental : Tout-en-un, 100 % hors-ligne, zéro LLM génératif, zéro Ollama.**
> RAG MCP Studio embarque directement son moteur d'embedding **ONNX Runtime** (`e5-small-v2-q`, 384 dimensions) à l'intérieur du JAR exécutable. Aucun service tiers (ni Ollama, ni API externe, ni téléchargement au démarrage) n'est requis pour vectoriser ou rechercher. La génération de réponse reste entièrement déléguée à votre client MCP (ex. OpenCode connecté à Mistral ou au LLM de votre choix).

---

## Fonctionnalités clés

- **Tout-en-un et souverain** : un seul fat-JAR (`target/rag-mcp-studio.jar`) embarquant le modèle d'embedding ONNX quantifié `e5-small-v2-q`. Fonctionne en **air-gap / hors-ligne complet** sur Windows, macOS et Linux.
- **Base SQLite portable (`.db`)** : un seul fichier contient les métadonnées, les chunks, les embeddings binaires (384 dimensions) et l'index plein texte **FTS5**. Facile à générer sur une machine puissante puis à distribuer sur des postes bureautiques sans transférer les documents sources.
- **Recherche hybride (Vectorielle + FTS5 + RRF)** : combine la similarité cosinus sur les embeddings ONNX et la recherche lexicale SQLite FTS5 via *Reciprocal Rank Fusion* (RRF).
- **Serveur MCP dynamique embarqué** : protocole Streamable HTTP (JSON-RPC 2.0, spécification `2025-03-26`), démarrable, redémarrable et arrêtable à chaud depuis l'interface Web sur le port de votre choix.
- **Interface Web autonome** : zéro dépendance CDN externe, thème clair/sombre, explorateur de fichiers intégré, suivi d'ingestion en temps réel (SSE) et banc de test de recherche intégré.

---

## Sommaire

1. [Prérequis](#prérequis)
2. [Installation et lancement](#installation-et-lancement)
3. [Tutoriel de mise en œuvre pas à pas](#tutoriel-de-mise-en-œuvre-pas-à-pas)
   - [Étape 1 — Vectoriser un dossier de documents](#étape-1--vectoriser-un-dossier-de-documents)
   - [Étape 2 — Inspecter la base et démarrer le serveur MCP](#étape-2--inspecter-la-base-et-démarrer-le-serveur-mcp)
   - [Étape 3 — Tester la recherche dans le studio](#étape-3--tester-la-recherche-dans-le-studio)
   - [Étape 4 — Connecter OpenCode (ou un autre client MCP)](#étape-4--connecter-opencode-ou-un-autre-client-mcp)
   - [Étape 5 — Partager une base `.db` en équipe](#étape-5--partager-une-base-db-en-équipe)
4. [Outils MCP exposés](#outils-mcp-exposés)
5. [API REST & SSE](#api-rest--sse)
6. [Architecture hexagonale](#architecture-hexagonale)
7. [Développement et tests](#développement-et-tests)
8. [Dépannage](#dépannage)
9. [Licence](#licence)

---

## Prérequis

- **Pour exécuter le JAR compilé** : uniquement **Java 21** (`java -version`). Aucun autre logiciel ou service externe n'est nécessaire.
- **Pour compiler depuis les sources** : **Java 21** et **Maven 3.9+** (`mvn -version`).

> **Note Windows :** ONNX Runtime s'appuie sur le runtime Microsoft Visual C++ (`msvcp140.dll` ≥ 14.40), inclus dans les distributions récentes du JDK 21 (Temurin / Microsoft Build of OpenJDK) ou via le pack [Microsoft Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist).

---

## Installation et lancement

### 1. Cloner le dépôt

```bash
git clone https://github.com/marqus17/ragmcpstudio.git
cd ragmcpstudio
```

### 2. Compiler le fat-JAR autonome

```bash
mvn clean package
```

Le binaire autonome (incluant le moteur ONNX et le modèle `e5-small-v2-q`) est généré dans `target/rag-mcp-studio.jar`.

### 3. Lancer l'application

```bash
java -jar target/rag-mcp-studio.jar
```

> **Astuces :**
> - Pour changer le port de l'interface Web (par défaut `8080`) :
>   ```bash
>   java -jar target/rag-mcp-studio.jar --server.port=9090
>   ```
> - Pour les bases volumineuses (> 50 000 chunks), vous pouvez allouer davantage de mémoire JVM :
>   ```bash
>   java -Xmx2g -jar target/rag-mcp-studio.jar
>   ```

### 4. Ouvrir l'interface Web

Rendez-vous dans votre navigateur à l'adresse :

```text
http://localhost:8080
```

---

## Tutoriel de mise en œuvre pas à pas

### Étape 1 — Vectoriser un dossier de documents

1. Ouvrez l'onglet **Vectorisation (Ingestion)**.
2. Renseignez les champs de configuration :
   - **Dossier source des documents** : chemin du répertoire contenant vos fichiers (saisie directe ou bouton **Parcourir...**).
   - **Fichier SQLite de sortie (`.db`)** : chemin du fichier `.db` à créer (ex. `/chemin/vers/projet-rag.db`).
   - **Taille de chunk** : `1000` caractères par défaut (le découpage respecte les titres Markdown).
   - **Chevauchement (overlap)** : `200` caractères par défaut.
3. Cliquez sur **Démarrer la vectorisation**.
4. Suivez l'avancement en direct dans le panneau **Progression en temps réel** (barre de progression, nombre de fichiers traités, chunks créés, durée et journal SSE). Vous pouvez interrompre le traitement à tout moment avec **Annuler**.

**Formats de fichiers pris en charge :**
`.md`, `.txt`, `.pdf` (via Apache PDFBox), `.java`, `.kt`, `.py`, `.ts`, `.json`, `.yml`.

**Dossiers ignorés automatiquement :**
`.git`, `node_modules`, `target`, `build` ainsi que tous les dossiers cachés commençant par `.`.

Une fois la vectorisation terminée, cliquez sur **Ouvrir cette base dans l'onglet MCP →** pour passer directement à l'étape suivante.

---

### Étape 2 — Inspecter la base et démarrer le serveur MCP

1. Dans l'onglet **Serveur MCP & Partage** :
   - Sélectionnez votre fichier `.db` (ou utilisez le bouton **Parcourir...**).
   - Cliquez sur **Inspecter la base** pour afficher ses métadonnées (`e5-small-v2-q`, `384` dimensions, nombre de documents, nombre de chunks, date de création) ainsi que la liste dépliable des sources indexées.
2. Dans la carte **Contrôle du serveur MCP** :
   - Choisissez le **Port MCP** (`8081` par défaut).
   - Cliquez sur **Démarrer**.
3. Le badge en haut à droite passe au vert : votre serveur MCP écoute désormais sur :
   ```text
   http://localhost:8081/mcp
   ```

Vous pouvez **Redémarrer** (par exemple après changement de base ou de port) ou **Arrêter** le serveur MCP à chaud sans redémarrer l'application.

---

### Étape 3 — Tester la recherche dans le studio

Avant même de brancher un client MCP, validez la qualité de votre index dans le panneau **Tester la recherche** (en bas de l'onglet **Serveur MCP & Partage**) :

1. Saisissez une question ou des mots-clés.
2. Ajustez `top_k` (3, 5, 8, 10 ou 15 résultats).
3. Cliquez sur **Rechercher**.

Chaque résultat affiche :
- La source et le titre du document.
- Le **score global RRF** ainsi que le détail du score **Cosinus** (sémantique ONNX) et du score **FTS5** (lexical).
- L'extrait de texte correspondant.

---

### Étape 4 — Connecter OpenCode (ou un autre client MCP)

Dans la carte **Configuration `opencode.json`**, cliquez sur **Copier** puis collez le bloc dans votre fichier `opencode.json` :

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "local-rag": {
      "type": "remote",
      "url": "http://localhost:8081/mcp",
      "enabled": true
    }
  }
}
```

Lancez ensuite OpenCode : il découvre automatiquement les outils MCP exposés par **RAG MCP Studio** (`search_docs` et `list_indexed_sources`) et interroge votre base documentaire locale pendant vos sessions.

> **Compatibilité :** Tout client MCP supportant le transport **Streamable HTTP** (`POST /mcp` JSON-RPC 2.0, `GET /mcp` SSE keep-alive, `DELETE /mcp` fermeture de session) peut se connecter à l'URL `http://localhost:<port>/mcp`.

---

### Étape 5 — Partager une base `.db` en équipe

Le fichier `.db` généré est **entièrement autonome** : il embarque les métadonnées, les chunks textuels, les vecteurs binaires et l'index FTS5.

Flux recommandé en entreprise :
1. Vectorisez vos corpus sur une machine dédiée (ou en CI) pour produire un fichier `connaissances.db`.
2. Distribuez `rag-mcp-studio.jar` et `connaissances.db` sur les postes utilisateurs (les documents originaux ne sont pas nécessaires).
3. Sur chaque poste, lancez `java -jar rag-mcp-studio.jar`, sélectionnez `connaissances.db` dans l'onglet **Serveur MCP & Partage** et cliquez sur **Démarrer** — aucun modèle externe à installer, tout fonctionne immédiatement hors-ligne.

---

## Outils MCP exposés

Le serveur MCP (`rag-mcp-studio`, protocole `2025-03-26`) expose deux outils :

| Outil | Paramètres | Description |
| :--- | :--- | :--- |
| `search_docs` | `query` (`string`, requis), `top_k` (`integer`, optionnel, `1` à `50`, défaut `5`) | Recherche hybride sémantique (ONNX + cosinus) + plein texte (SQLite FTS5) fusionnée par RRF. |
| `list_indexed_sources` | *(aucun)* | Liste l'ensemble des documents indexés dans la base active avec leur nombre de chunks. |

---

## API REST & SSE

Si vous souhaitez automatiser l'ingestion ou le pilotage du serveur MCP depuis un script :

| Méthode | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/ingestion/start` | Démarre une ingestion (`sourceDir`, `dbPath`, `chunkSize`, `chunkOverlap`). |
| `POST` | `/api/ingestion/cancel` | Annule l'ingestion en cours. |
| `GET` | `/api/ingestion/status` | Retourne l'état courant de l'ingestion. |
| `GET` | `/api/ingestion/events` | Flux SSE temps réel des événements `progress`. |
| `GET` | `/api/db/inspect?path=...` | Inspecte une base SQLite `.db` (métadonnées et sources indexées). |
| `GET` | `/api/mcp/status` | Retourne le statut du serveur MCP embarqué. |
| `POST` | `/api/mcp/start` | Démarre le serveur MCP (`port`, `dbPath`). |
| `POST` | `/api/mcp/restart` | Redémarre le serveur MCP avec de nouveaux paramètres. |
| `POST` | `/api/mcp/stop` | Arrête le serveur MCP. |
| `POST` | `/api/search/test` | Exécute une recherche hybride de test (`dbPath`, `query`, `topK`). |
| `GET` | `/api/fs/list?path=...&mode=dir\|db` | Liste les répertoires (et fichiers `.db`/`.sqlite`/`.sqlite3` en mode `db`). |

---

## Architecture hexagonale

Le projet suit une architecture propre **Ports & Adapters** en Java 21 (sans Lombok, virtual threads activés) :

```text
src/main/java/com/ragmcp/studio/
├── domain/          # Modèles purs Java 21 (record), ports et services métier
│   ├── model/       # Chunk, DatabaseMetadata, DocumentContent, IngestionConfig, SearchResult...
│   ├── port/        # EmbeddingPort, VectorStorePort, DocumentExtractorPort, McpServerControllerPort
│   └── service/     # DocumentChunker (Markdown-aware), VectorMath (cosinus + float[]/byte[]), RrfFusion
├── application/     # Cas d'usage : IngestionService (virtual threads + SSE) et SearchService (hybride)
├── adapter/
│   ├── in/
│   │   ├── mcp/     # DynamicMcpServerAdapter (serveur HTTP/JSON-RPC 2.0 Streamable HTTP embarqué)
│   │   └── web/     # Contrôleurs REST/SSE + DTOs + gestion d'erreurs
│   └── out/
│       ├── sqlite/   # SqliteVectorStoreAdapter (tables metadata, chunks, FTS5 + cache vectoriel)
│       ├── embedding/# EmbeddedOnnxEmbeddingAdapter (ONNX Runtime embarqué, modèle e5-small-v2-q)
│       └── document/ # FilesystemDocumentExtractor (texte, code, Markdown, PDFBox)
└── config/          # Câblage Spring Boot (BeanConfiguration)
```

---

## Développement et tests

Lancer la suite de tests unitaires et d'intégration (incluant l'inférence ONNX réelle hors-ligne) :

```bash
mvn test
```

Lancer l'application directement en mode développement sans packager le JAR :

```bash
mvn spring-boot:run
```

---

## Dépannage

- **Port `8080` ou `8081` déjà utilisé** :
  - Pour l'interface Web : `java -jar target/rag-mcp-studio.jar --server.port=8090`
  - Pour le serveur MCP : modifiez simplement le champ **Port MCP** dans l'onglet **Serveur MCP & Partage**.
- **Erreur `Database dimensions (...) do not match embedded model`** : la base `.db` a été générée avec un autre modèle d'embedding (dimensions incompatibles). Ré-indexez vos documents avec cette version autonome.
- **Aucun fichier trouvé lors de l'ingestion** : vérifiez que vos fichiers possèdent une extension prise en charge (`.md`, `.txt`, `.pdf`, `.java`, `.kt`, `.py`, `.ts`, `.json`, `.yml`) et ne sont pas situés dans un dossier ignoré (`.git`, `node_modules`, `target`, `build` ou dossier caché).
- **Erreur `UnsatisfiedLinkError` sous Windows** : mettez à jour votre JDK 21 ou installez le [Microsoft Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist) récent pour ONNX Runtime.

---

## Licence

Ce projet est distribué sous licence **Apache 2.0**. Voir le fichier [LICENSE](LICENSE) pour plus de détails.
