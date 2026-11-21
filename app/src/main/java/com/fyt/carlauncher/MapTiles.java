package com.fyt.carlauncher;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

/**
 * Fonds de carte, toujours sombres :
 *  - Stadia « Alidade Smooth Dark » (style proche d'Apple Plans la nuit) quand la clé Stadia est présente ;
 *  - sinon OpenStreetMap standard assombri par un filtre de couleurs (aucune clé nécessaire).
 */
final class MapTiles {

    // ---- Stadia
    /** Tuiles @2x (512 px) affichées à 384 px : nettes, noms de rue lisibles. */
    static final float STADIA_SCALE = 0.75f;

    static XYTileSource stadiaDark() {
        return new XYTileSource("StadiaSmoothDark2x", 0, 20, 512, "@2x.png?api_key=" + StadiaApi.KEY,
                new String[]{"https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/"},
                "© Stadia Maps © OpenMapTiles © OpenStreetMap");
    }

    // ---- OpenStreetMap assombri (secours)
    static final ITileSource SOURCE = TileSourceFactory.MAPNIK;

    /** Tuiles 256 px affichées un peu plus grandes pour des noms de rue lisibles en conduisant. */
    static final float SCALE = 1.17f;

    private MapTiles() {}

    /** invert(1) → hue-rotate(180°) → brightness(0.95) → contrast(0.9) → saturate(0.55). */
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
