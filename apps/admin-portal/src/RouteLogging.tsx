import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { portalLogging } from './portal-logging';

export function RouteLogging() {
  const location = useLocation();
  useEffect(() => { portalLogging.record('ROUTE', 'ui.route.changed'); }, [location.key]);
  return null;
}
