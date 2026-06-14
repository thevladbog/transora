import { useEffect, useMemo, useRef, useState } from 'react';
import { MapContainer, Marker, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useTranslation } from 'react-i18next';
import { geocodeReverse, geocodeSearch, type GeocodeResult } from '@transora/api-client';
import { FormTextField } from '@/components/ui/FormFields';

const defaultCenter: [number, number] = [55.7558, 37.6173];

const markerIcon = L.icon({
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
  iconSize: [25, 41],
  iconAnchor: [12, 41],
});

type PointMapPickerProps = {
  latitude: number;
  longitude: number;
  address: string;
  city: string;
  onLocationChange: (patch: { latitude: number; longitude: number; address?: string; city?: string }) => void;
};

function MapClickHandler({
  onPick,
}: {
  onPick: (lat: number, lon: number, fromMapClick: boolean) => void;
}) {
  useMapEvents({
    click(event) {
      onPick(event.latlng.lat, event.latlng.lng, true);
    },
  });
  return null;
}

function MapAttribution() {
  const map = useMap();
  useEffect(() => {
    map.attributionControl.setPrefix(false);
  }, [map]);
  return null;
}

function MapViewSync({ position }: { position: [number, number] }) {
  const map = useMap();
  const prevPositionRef = useRef<[number, number] | null>(null);

  useEffect(() => {
    const prev = prevPositionRef.current;
    prevPositionRef.current = position;
    if (!prev || (prev[0] === position[0] && prev[1] === position[1])) {
      return;
    }
    map.flyTo(position, Math.max(map.getZoom(), 14), { duration: 0.4 });
  }, [map, position]);

  return null;
}

export function PointMapPicker({ latitude, longitude, address, city, onLocationChange }: PointMapPickerProps) {
  const { t } = useTranslation('points');
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<GeocodeResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchAttempted, setSearchAttempted] = useState(false);

  const position = useMemo<[number, number]>(
    () => [latitude || defaultCenter[0], longitude || defaultCenter[1]],
    [latitude, longitude],
  );

  useEffect(() => {
    const trimmed = query.trim();
    if (trimmed.length < 3) {
      setResults([]);
      setIsSearching(false);
      setSearchAttempted(false);
      return;
    }

    setIsSearching(true);
    setSearchAttempted(false);
    const timer = window.setTimeout(() => {
      void geocodeSearch(trimmed, 10, city)
        .then((response) => {
          setResults(response.data);
          setSearchAttempted(true);
        })
        .catch(() => {
          setResults([]);
          setSearchAttempted(true);
        })
        .finally(() => setIsSearching(false));
    }, 400);
    return () => window.clearTimeout(timer);
  }, [city, query]);

  async function pickLocation(lat: number, lon: number, fromMapClick = false) {
    if (fromMapClick) {
      setQuery('');
      setResults([]);
      setSearchAttempted(false);
    }
    onLocationChange({ latitude: lat, longitude: lon });
    try {
      const response = await geocodeReverse(lat, lon);
      const result = response.data;
      if (result) {
        onLocationChange({
          latitude: lat,
          longitude: lon,
          address: result.address ?? result.displayName,
          city: result.city,
        });
      }
    } catch {
      // keep coordinates only
    }
  }

  return (
    <div className="space-y-3">
      <FormTextField
        label={t('searchLabel')}
        name="geocodeSearch"
        value={query}
        onChange={setQuery}
      />
      <p className="text-sm text-muted">{t('searchHint')}</p>
      {isSearching ? <p className="text-sm text-muted">{t('searchLoading')}</p> : null}
      {!isSearching && searchAttempted && results.length === 0 ? (
        <p className="text-sm text-muted">{t('searchEmpty')}</p>
      ) : null}
      {results.length > 0 ? (
        <ul className="max-h-40 overflow-y-auto rounded-lg border border-border text-sm">
          {results.map((result) => (
            <li key={`${result.latitude}-${result.longitude}-${result.displayName}`}>
              <button
                type="button"
                className="w-full px-3 py-2 text-left hover:bg-default"
                onClick={() => {
                  onLocationChange({
                    latitude: result.latitude,
                    longitude: result.longitude,
                    address: result.address ?? result.displayName,
                    city: result.city,
                  });
                  setQuery('');
                  setResults([]);
                  setSearchAttempted(false);
                }}
              >
                {result.address ?? result.displayName}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="h-64 overflow-hidden rounded-lg border border-border">
        <MapContainer center={position} zoom={12} scrollWheelZoom className="h-full w-full">
          <TileLayer
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          />
          <MapAttribution />
          <MapViewSync position={position} />
          <MapClickHandler onPick={pickLocation} />
          <Marker key={`${latitude}:${longitude}`} position={position} icon={markerIcon} />
        </MapContainer>
      </div>
      <p className="text-sm text-muted">{t('mapHint')}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <FormTextField label={t('latitude')} name="latitude" value={String(latitude)} onChange={(v) => onLocationChange({ latitude: Number(v), longitude, address, city })} />
        <FormTextField label={t('longitude')} name="longitude" value={String(longitude)} onChange={(v) => onLocationChange({ latitude, longitude: Number(v), address, city })} />
      </div>
    </div>
  );
}
