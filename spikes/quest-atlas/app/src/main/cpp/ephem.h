// Low-precision solar-system ephemeris for the star atlas: Sun, Moon, the planets and Kepler-propagated asteroids.
// Plain C++ with no Android / GL dependency, so tools/test_ephem.cpp can check it on the PC against astropy.
//
//  planets   JPL "Keplerian elements for approximate positions of the major planets", Table 1 (1800-2050 AD),
//            good to about an arcminute
//  Moon      Schlyter's perturbed-elements series (about 0.1-0.2 degrees)
//  asteroid  osculating elements propagated as a plain Kepler orbit (fine within a few months of the epoch)
#pragma once

#include <algorithm>
#include <cmath>

namespace ephem {

constexpr double kPi = 3.14159265358979323846;
constexpr double kD2R = kPi / 180.0;
constexpr double kAuKm = 149597870.7;
constexpr double kEarthRadiusKm = 6378.137;

struct V3 { double x = 0, y = 0, z = 0; };
inline V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator*(V3 a, double k) { return {a.x * k, a.y * k, a.z * k}; }
inline double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline double norm(V3 a) { return std::sqrt(dot(a, a)); }
inline V3 unit(V3 a) { double n = norm(a); return n > 0 ? a * (1.0 / n) : V3{}; }

inline double jdFromUnix(double t) { return t / 86400.0 + 2440587.5; }
inline double wrap180(double deg) {
    deg = std::fmod(deg, 360.0);
    if (deg > 180.0) deg -= 360.0;
    if (deg < -180.0) deg += 360.0;
    return deg;
}

inline double solveKepler(double M, double e) {  // radians
    double E = e < 0.8 ? M : kPi;
    for (int i = 0; i < 50; ++i) {
        double d = (E - e * std::sin(E) - M) / (1.0 - e * std::cos(E));
        E -= d;
        if (std::fabs(d) < 1e-12) break;
    }
    return E;
}

// Position in the orbital plane rotated into the ecliptic: a [any length unit], angles in radians.
inline V3 orbitToEcl(double a, double e, double I, double Om, double w, double M) {
    double E = solveKepler(M, e);
    double xp = a * (std::cos(E) - e), yp = a * std::sqrt(1.0 - e * e) * std::sin(E);
    double cw = std::cos(w), sw = std::sin(w), cO = std::cos(Om), sO = std::sin(Om), cI = std::cos(I), sI = std::sin(I);
    return {(cw * cO - sw * sO * cI) * xp + (-sw * cO - cw * sO * cI) * yp,
            (cw * sO + sw * cO * cI) * xp + (-sw * sO + cw * cO * cI) * yp,
            (sw * sI) * xp + (cw * sI) * yp};
}

// Ecliptic -> equatorial, rotating about x by the obliquity (radians).
inline V3 rotateObliquity(V3 v, double eps) {
    double c = std::cos(eps), s = std::sin(eps);
    return {v.x, v.y * c - v.z * s, v.y * s + v.z * c};
}
constexpr double kEpsJ2000 = 23.43928 * kD2R;

// ---------------------------------------------------------------- planets
struct Elem { double a, da, e, de, I, dI, L, dL, w, dw, Om, dOm; };  // value and rate per Julian century (degrees, AU)
enum { MERCURY, VENUS, EMB, MARS, JUPITER, SATURN, URANUS, NEPTUNE, kPlanets };
inline const Elem* elements() {
    static const Elem el[kPlanets] = {
        {0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081},
        {0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418},
        {1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0},
        {1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343},
        {5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106},
        {9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794},
        {19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939, 313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589},
        {30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372, -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664}};
    return el;
}

// Heliocentric ecliptic J2000 position in AU; k = EMB gives the Earth-Moon barycentre.
inline V3 planetHelioEcl(int k, double jd) {
    const Elem& p = elements()[k];
    double T = (jd - 2451545.0) / 36525.0;
    double a = p.a + p.da * T, e = p.e + p.de * T, I = p.I + p.dI * T, L = p.L + p.dL * T;
    double w = p.w + p.dw * T, Om = p.Om + p.dOm * T;
    return orbitToEcl(a, e, I * kD2R, Om * kD2R, (w - Om) * kD2R, wrap180(L - w) * kD2R);
}

// ---------------------------------------------------------------- Moon
struct MoonPos {
    V3 eqDate;      // geocentric, equator of date, Earth radii
    V3 eclDate;     // geocentric, ecliptic of date, Earth radii
    double distEr;
};
inline MoonPos moonGeo(double jd) {
    double d = jd - 2451543.5;  // days since 1999 Dec 31.0
    double N = (125.1228 - 0.0529538083 * d) * kD2R, i = 5.1454 * kD2R;
    double w = (318.0634 + 0.1643573223 * d) * kD2R, e = 0.054900;
    double Mm = (115.3654 + 13.0649929509 * d) * kD2R;
    double E = solveKepler(Mm, e);
    double xv = 60.2666 * (std::cos(E) - e), yv = 60.2666 * std::sqrt(1 - e * e) * std::sin(E);
    double v = std::atan2(yv, xv), r = std::hypot(xv, yv);
    double xh = r * (std::cos(N) * std::cos(v + w) - std::sin(N) * std::sin(v + w) * std::cos(i));
    double yh = r * (std::sin(N) * std::cos(v + w) + std::cos(N) * std::sin(v + w) * std::cos(i));
    double zh = r * std::sin(v + w) * std::sin(i);
    double lon = std::atan2(yh, xh), lat = std::atan2(zh, std::hypot(xh, yh));

    double Ms = (356.0470 + 0.9856002585 * d) * kD2R, ws = (282.9404 + 4.70935e-5 * d) * kD2R;
    double Lm = Mm + w + N, Ls = Ms + ws, D = Lm - Ls, F = Lm - N;
    lon += kD2R * (-1.274 * std::sin(Mm - 2 * D) + 0.658 * std::sin(2 * D) - 0.186 * std::sin(Ms) -
                   0.059 * std::sin(2 * Mm - 2 * D) - 0.057 * std::sin(Mm - 2 * D + Ms) + 0.053 * std::sin(Mm + 2 * D) +
                   0.046 * std::sin(2 * D - Ms) + 0.041 * std::sin(Mm - Ms) - 0.035 * std::sin(D) -
                   0.031 * std::sin(Mm + Ms) - 0.015 * std::sin(2 * F - 2 * D) + 0.011 * std::sin(Mm - 4 * D));
    lat += kD2R * (-0.173 * std::sin(F - 2 * D) - 0.055 * std::sin(Mm - F - 2 * D) - 0.046 * std::sin(Mm + F - 2 * D) +
                   0.033 * std::sin(F + 2 * D) + 0.017 * std::sin(2 * Mm + F));
    r += -0.58 * std::cos(Mm - 2 * D) - 0.46 * std::cos(2 * D);

    MoonPos m;
    m.distEr = r;
    m.eclDate = {r * std::cos(lat) * std::cos(lon), r * std::cos(lat) * std::sin(lon), r * std::sin(lat)};
    m.eqDate = rotateObliquity(m.eclDate, (23.4393 - 3.563e-7 * d) * kD2R);
    return m;
}

// ---------------------------------------------------------------- bodies as seen from the Earth
struct Seen {
    V3 dir;            // unit vector, equatorial J2000 (Moon: equator of date, see main.cpp)
    double distAu = 0; // from the Earth
    double rAu = 0;    // from the Sun
    double phaseDeg = 0;
};

// Earth's heliocentric ecliptic position: barycentre minus the Moon's share (1/82.3 of the geocentric Moon vector).
inline V3 earthHelioEcl(double jd, const MoonPos& moon) {
    V3 emb = planetHelioEcl(EMB, jd);
    return emb - moon.eclDate * (kEarthRadiusKm / kAuKm / 82.30056);
}

// Geocentric equatorial J2000 direction/distance of a heliocentric ecliptic point.
inline Seen seenFrom(V3 helioEcl, V3 earthEcl) {
    Seen s;
    V3 g = helioEcl - earthEcl;
    s.distAu = norm(g);
    s.rAu = norm(helioEcl);
    s.dir = unit(rotateObliquity(g, kEpsJ2000));
    double R = norm(earthEcl);
    double c = (s.rAu * s.rAu + s.distAu * s.distAu - R * R) / (2 * s.rAu * s.distAu);
    s.phaseDeg = std::acos(std::clamp(c, -1.0, 1.0)) / kD2R;
    return s;
}

// Apparent visual magnitudes (Astronomical Almanac style); Saturn includes a typical ring contribution.
inline double planetMag(int k, double rAu, double dAu, double iDeg) {
    double l = 5 * std::log10(rAu * dAu);
    switch (k) {
        case MERCURY: return -0.36 + l + 0.027 * iDeg + 2.2e-13 * std::pow(iDeg, 6);
        case VENUS: return -4.34 + l + 0.013 * iDeg + 4.2e-7 * std::pow(iDeg, 3);
        case MARS: return -1.51 + l + 0.016 * iDeg;
        case JUPITER: return -9.25 + l + 0.014 * iDeg;
        case SATURN: return -9.0 + l + 0.044 * iDeg;
        case URANUS: return -7.19 + l;
        default: return -6.87 + l;
    }
}
inline double planetRadiusKm(int k) {
    static const double r[kPlanets] = {2439.7, 6051.8, 6371.0, 3389.5, 69911.0, 58232.0, 25362.0, 24622.0};
    return r[k];
}

// ---------------------------------------------------------------- asteroids
struct Asteroid {
    double H, G;                 // absolute magnitude, slope
    double a, e, I, Om, w, M0;   // AU, degrees (M0 at epochJd)
    double epochJd;
};
inline V3 asteroidHelioEcl(const Asteroid& s, double jd) {
    double n = 0.9856076686 / (s.a * std::sqrt(s.a));  // degrees per day
    double M = wrap180(s.M0 + n * (jd - s.epochJd));
    return orbitToEcl(s.a, s.e, s.I * kD2R, s.Om * kD2R, s.w * kD2R, M * kD2R);
}
// H,G system with the usual two-term phase function.
inline double asteroidMag(const Asteroid& s, double rAu, double dAu, double phaseDeg) {
    double al = phaseDeg * kD2R;
    double t = std::tan(al / 2);
    double p1 = std::exp(-3.33 * std::pow(t, 0.63)), p2 = std::exp(-1.87 * std::pow(t, 1.22));
    double ph = (1 - s.G) * p1 + s.G * p2;
    return s.H + 5 * std::log10(rAu * dAu) - 2.5 * std::log10(std::max(ph, 1e-6));
}

// ---------------------------------------------------------------- observer
// Observer's geocentric equatorial position in Earth radii for a local sidereal angle (radians).
inline V3 observerEr(double latRad, double heightM, double lstRad) {
    const double f = 1.0 / 298.257223563, e2 = f * (2 - f);
    double s = std::sin(latRad), c = std::cos(latRad);
    double N = 1.0 / std::sqrt(1 - e2 * s * s), h = heightM / (kEarthRadiusKm * 1000.0);
    return {(N + h) * c * std::cos(lstRad), (N + h) * c * std::sin(lstRad), (N * (1 - e2) + h) * s};
}

}  // namespace ephem
