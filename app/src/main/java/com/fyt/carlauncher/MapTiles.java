package com.fyt.carlauncher;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;

/**
 * Fond de carte : OpenStreetMap standard (données les plus complètes et à jour, sans clé API),
 * rendu toujours sombre par un filtre de couleurs appliqué aux tuiles.
 *
 * Filtre = invert(1) → hue-rotate(180°) → brightness(0.95) → contrast(0.9) → saturate(0.55)
 * (mêmes valeurs que la maquette docs/preview/home.html) : fond gris foncé, routes claires,
 * l'eau et les parcs gardent des teintes naturelles.
 */
final class MapTiles {

    static final ITileSource SOURCE = TileSourceFactory.MAPNIK;

    /** Tuiles 256 px affichées un peu plus grandes pour des noms de rue lisibles en conduisant. */
    static final float SCALE = 1.17f;

    private MapTiles() {}

    static ColorMatrixColorFilter darkFilter() {
        ColorMatrix m = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        m.postConcat(hueRotate180());
        m.postConcat(scale(0.95f, 0f));                       // luminosité
        m.postConcat(scale(0.9f, 128f * (1f - 0.9f)));        // contraste
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.55f);
        m.postConcat(sat);
        return new ColorMatrixColorFilter(m);
    }

    private static ColorMatrix hueRotate180() {
        return new ColorMatrix(new float[]{
                -0.574f, 1.430f, 0.144f, 0, 0,
                0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0,
                0, 0, 0, 1, 0});
    }

    private static ColorMatrix scale(float k, float offset) {
        return new ColorMatrix(new float[]{
                k, 0, 0, 0, offset,
                0, k, 0, 0, offset,
                0, 0, k, 0, offset,
                0, 0, 0, 1, 0});
    }
}
