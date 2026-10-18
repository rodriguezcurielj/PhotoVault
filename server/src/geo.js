// Offline reverse geocoding: GPS -> nearest city, using GeoNames data downloaded at image build time.
const fs = require('fs');
const path = require('path');
const { GEO_DIR } = require('./config');

let grid = null; // "lat,lon" (whole degrees) -> [{ lat, lon, city, region, country }]

function load() {
  grid = new Map();
  const file = f => path.join(GEO_DIR, f);
  if (!fs.existsSync(file('cities15000.txt'))) { console.warn('geo: no GeoNames data, places disabled'); return; }

  const countries = new Map();
  for (const line of fs.readFileSync(file('countryInfo.txt'), 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const c = line.split('\t');
    countries.set(c[0], c[4]);
  }
  const regions = new Map();
  for (const line of fs.readFileSync(file('admin1CodesASCII.txt'), 'utf8').split('\n')) {
    const c = line.split('\t');
    if (c.length > 1) regions.set(c[0], c[1]);
  }
  for (const line of fs.readFileSync(file('cities15000.txt'), 'utf8').split('\n')) {
    const c = line.split('\t');
    // Skip city districts (PPLX) so places read "Chicago", not "Chicago Loop".
    if (c.length < 11 || c[7] === 'PPLX') continue;
    const lat = +c[4], lon = +c[5];
    const key = `${Math.floor(lat)},${Math.floor(lon)}`;
    if (!grid.has(key)) grid.set(key, []);
    grid.get(key).push({ lat, lon, city: c[1], region: regions.get(`${c[8]}.${c[10]}`) || null, country: countries.get(c[8]) || c[8] });
  }
}

function lookup(lat, lon) {
  if (lat == null || lon == null) return null;
  if (!grid) load();
  let best = null, bestD = Infinity;
  for (let r = 0; r <= 2 && !best; r++) {
    for (let a = -r; a <= r; a++) for (let b = -r; b <= r; b++) {
      for (const p of grid.get(`${Math.floor(lat) + a},${Math.floor(lon) + b}`) || []) {
        const d = (p.lat - lat) ** 2 + ((p.lon - lon) * Math.cos(lat * Math.PI / 180)) ** 2;
        if (d < bestD) { bestD = d; best = p; }
      }
    }
  }
  return best && { city: best.city, region: best.region, country: best.country };
}

module.exports = { lookup };
