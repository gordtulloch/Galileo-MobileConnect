// Host-side check of app/src/main/cpp/ephem.h against astropy (tools/test_ephem.py builds and runs this).
// Prints "name ra_deg dec_deg mag diameter_arcmin" per body, equatorial J2000 (Moon: equator of date).
#include <cstdio>
#include <cstdlib>

#include "../app/src/main/cpp/ephem.h"

using namespace ephem;

static void out(const char* n, V3 d, double mag, double arcmin) {
    double ra = std::atan2(d.y, d.x) / kD2R;
    if (ra < 0) ra += 360;
    std::printf("%s %.6f %.6f %.3f %.4f\n", n, ra, std::asin(d.z) / kD2R, mag, arcmin);
}

int main(int argc, char** argv) {
    double unixT = argc > 1 ? std::atof(argv[1]) : 1.7e9;
    double jd = jdFromUnix(unixT);
    MoonPos moon = moonGeo(jd);
    V3 earth = earthHelioEcl(jd, moon);
    out("Moon", unit(moon.eqDate), 0, 2 * std::asin(1737.4 / (moon.distEr * kEarthRadiusKm)) / kD2R * 60);
    Seen sun = seenFrom(V3{}, earth);
    out("Sun", sun.dir, 0, 2 * std::asin(696000.0 / (sun.distAu * kAuKm)) / kD2R * 60);
    const char* names[kPlanets] = {"Mercury", "Venus", "EMB", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    for (int k = 0; k < kPlanets; ++k) {
        if (k == EMB) continue;
        Seen s = seenFrom(planetHelioEcl(k, jd), earth);
        out(names[k], s.dir, planetMag(k, s.rAu, s.distAu, s.phaseDeg),
            2 * std::asin(planetRadiusKm(k) / (s.distAu * kAuKm)) / kD2R * 60);
    }
    if (argc >= 11) {  // asteroid: H G a e I Om w M0 epochJd
        Asteroid a{std::atof(argv[2]), std::atof(argv[3]), std::atof(argv[4]), std::atof(argv[5]), std::atof(argv[6]),
                   std::atof(argv[7]), std::atof(argv[8]), std::atof(argv[9]), std::atof(argv[10])};
        Seen s = seenFrom(asteroidHelioEcl(a, jd), earth);
        out("Asteroid", s.dir, asteroidMag(a, s.rAu, s.distAu, s.phaseDeg), 0);
    }
    return 0;
}
