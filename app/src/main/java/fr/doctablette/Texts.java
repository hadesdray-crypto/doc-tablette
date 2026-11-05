package fr.doctablette;

/** Tous les textes longs de l'appli (en français). */
final class Texts {
    private Texts() {
    }

    static final String FIRST =
            "1. Sur la tablette : activez le « débogage USB » (voir « Aide : brancher la tablette »).\n\n"
                    + "2. Branchez la tablette à ce téléphone avec le câble. Sur la tablette, touchez « Autoriser » (cochez « Toujours autoriser »).\n\n"
                    + "3. Touchez « Bilan » pour voir la santé de la tablette, puis « Tout réparer ».\n\n"
                    + "Rien n'est envoyé sur Internet : tout se passe entre le téléphone et la tablette.";

    static final String HELP =
            "ACTIVER LE DÉBOGAGE USB SUR LA TABLETTE (une seule fois)\n"
                    + "1. Ouvrez « Paramètres » puis « À propos de la tablette » (parfois « Informations sur le logiciel »).\n"
                    + "2. Touchez 7 fois « Numéro de build ». Un message dit que vous êtes développeur.\n"
                    + "3. Revenez dans « Paramètres » puis « Options pour les développeurs ».\n"
                    + "4. Activez « Débogage USB ».\n\n"
                    + "BRANCHER\n"
                    + "Reliez la tablette au téléphone avec un câble qui transmet les données (pas un câble de charge seule). "
                    + "Si les deux prises sont différentes, utilisez un adaptateur OTG. Quand la tablette demande « Autoriser le débogage USB ? », touchez « Autoriser ».\n\n"
                    + "SI LA TABLETTE N'APPARAÎT PAS\n"
                    + "- Sur la tablette, tirez le bandeau du haut : touchez « Charge par USB » puis choisissez « Transfert de fichiers ».\n"
                    + "- Essayez l'autre câble ou l'autre prise.\n"
                    + "- Samsung : ouvrez « Paramètres », « Sécurité et confidentialité », « Auto Blocker », puis désactivez « Empêcher les commandes par câble USB » (ou désactivez Auto Blocker).\n"
                    + "- Si le téléphone demande l'accès USB, touchez « OK ».\n\n"
                    + "MISE À JOUR DE DOC TABLETTE\n"
                    + "Si Android demande d'autoriser l'installation d'applis pour Doc Tablette, touchez « Autoriser » puis revenez. "
                    + "Si Play Protect affiche un avertissement, touchez « Plus de détails » puis « Installer quand même ».\n\n"
                    + "TABLETTE QUI S'ÉTEINT TOUTE SEULE\n"
                    + "Certaines marques arrêtent les applis en arrière-plan. Laissez l'écran du téléphone allumé pendant une réparation.";

    static final String LEGAL_PLACEHOLDER_NOTE =
            "Les cases « À COMPLÉTER » sont à remplir par l'éditeur avant la mise en vente.";

    static final String MENTIONS =
            "MENTIONS LÉGALES\n\n"
                    + "Application : Doc Tablette\n\n"
                    + "Éditeur : [ À COMPLÉTER : nom de l'entreprise ]\n"
                    + "Forme : micro-entreprise\n"
                    + "SIRET : [ À COMPLÉTER ]\n"
                    + "Adresse : [ À COMPLÉTER ]\n"
                    + "Contact : [ À COMPLÉTER : adresse e-mail ]\n\n"
                    + "Hébergement des mises à jour : GitHub, Inc.";

    static final String CGU =
            "CONDITIONS D'UTILISATION\n\n"
                    + "1. Objet. Doc Tablette aide à tester, nettoyer, optimiser et mettre à jour une tablette Android branchée à votre téléphone.\n\n"
                    + "2. Vos appareils. Utilisez l'appli seulement sur des appareils qui vous appartiennent ou dont le propriétaire est d'accord. "
                    + "Vous activez vous-même le débogage USB et vous autorisez chaque connexion sur la tablette.\n\n"
                    + "3. Réparations. Les réglages proposés sont courants et la plupart sont annulables (animations, applis désactivées). "
                    + "L'appli ne répare pas le matériel (écran cassé, batterie usée). Faites une sauvegarde de vos données importantes avant toute opération.\n\n"
                    + "4. Licence. Le code d'activation reçu après le paiement donne droit à l'usage de la fonction « réparer » sur vos appareils. "
                    + "Il est personnel et ne doit pas être partagé.\n\n"
                    + "5. Responsabilité. L'appli est fournie sans garantie de résultat. L'éditeur ne peut être tenu responsable des données perdues "
                    + "ou d'un appareil mal utilisé, dans les limites permises par la loi.\n\n"
                    + "6. Droit applicable : droit français.\n\n"
                    + "Éditeur : [ À COMPLÉTER : nom de l'entreprise ]";

    static final String CONFIDENTIALITE =
            "POLITIQUE DE CONFIDENTIALITÉ\n\n"
                    + "Doc Tablette ne collecte aucune donnée personnelle.\n\n"
                    + "- Les informations lues sur la tablette (modèle, stockage, batterie, liste des applis) restent sur votre téléphone et ne sont envoyées nulle part.\n"
                    + "- La clé de sécurité qui permet de se connecter à la tablette est créée et gardée sur votre téléphone.\n"
                    + "- L'appli contacte Internet uniquement pour chercher une mise à jour sur GitHub. Cette requête ne contient aucune information vous concernant.\n"
                    + "- Le code d'activation est vérifié sur le téléphone, sans connexion.\n"
                    + "- Le bouton « ? » prépare un message d'erreur que vous copiez ou envoyez vous-même : rien ne part automatiquement.\n\n"
                    + "Responsable : [ À COMPLÉTER : nom de l'entreprise ]\n"
                    + "Contact : [ À COMPLÉTER : adresse e-mail ]";
}
