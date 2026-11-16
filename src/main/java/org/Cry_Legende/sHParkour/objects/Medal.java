package org.Cry_Legende.sHParkour.objects;

import java.util.List;

/**
 * Une médaille de temps d'un parcours (clé {@code medailles} du fichier du parcours).
 *
 * @param name      nom affiché, codes couleur « & » permis
 * @param maxTimeMs temps maximal en millisecondes ; 0 ou moins : médaille invalide, jamais obtenue
 * @param commands  commandes console exécutées une fois, quand la médaille est obtenue
 */
public record Medal(String name, long maxTimeMs, List<String> commands) {

    public boolean valid() {
        return maxTimeMs > 0;
    }
}
