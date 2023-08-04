import React from 'react';
import { useLocation } from 'react-router-dom';

export const REQUEST_STEPS_FLOW = ['MatchRoute', 'PreRoute', 'ValidateAccess', 'TransformRequest'];

export const firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

export const toUpperCaseLabels = (obj) => {
  return Object.entries(obj).reduce((acc, [key, value]) => {
    const isLabelField = key === 'label';
    const v = isLabelField && value ? value.replace(/_/g, ' ') : value;

    return {
      ...acc,
      [key]: !value
        ? null
        : isLabelField
          ? v.charAt(0).toUpperCase() + v.slice(1)
          : typeof value === 'object' &&
            value !== null &&
            key !== 'transformer' &&
            key !== 'optionsTransformer' &&
            !Array.isArray(value)
            ? toUpperCaseLabels(value)
            : value,
    };
  }, {});
};

export function useQuery() {
  const { search } = useLocation();
  return React.useMemo(() => new URLSearchParams(search), [search]);
}

export const useEntityFromURI = () => {
  const location = useLocation();
  return entityFromURI(location);
};

export const entityFromURI = (location) => {
  const { pathname } = location;

  let entity = 'routes';
  try {
    entity = pathname.split('/')[1];
  } catch (_) { }

  const isRouteInstance = entity === 'routes';

  return {
    isRouteInstance,
    capitalizePlural: isRouteInstance ? 'Routes' : 'Route Compositions',
    capitalize: isRouteInstance ? 'Route' : 'Route Composition',
    lowercase: isRouteInstance ? 'route' : 'route composition',
    fetchName: isRouteInstance ? 'ROUTES' : 'SERVICES',
    link: isRouteInstance ? 'routes' : 'route-compositions',
  };
};

export const MAX_GREEN_SCORE_NOTE = 6000;
const GREEN_SCORE_GRADES = {
  "#2ecc71": rank => rank >= MAX_GREEN_SCORE_NOTE,
  "#27ae60": rank => rank < MAX_GREEN_SCORE_NOTE && rank >= 3000,
  "#f1c40f": rank => rank < 3000 && rank >= 2000,
  "#d35400": rank => rank < 2000 && rank >= 1000,
  "#c0392b": rank => rank < 1000
}

export function calculateGreenScore(routeRules) {
  const { sections } = routeRules;

  const score = sections.reduce((acc, item) => {
    return acc + item.rules.reduce((acc, rule) => {
      return acc += (rule.enabled ? MAX_GREEN_SCORE_NOTE * (rule.section_weight / 100) * (rule.weight / 100) : 0)
    }, 0)
  }, 0);

  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex(grade => grade[1](score))

  return {
    score,
    rank: rankIdx === -1 ? "Not evaluated" : Object.keys(GREEN_SCORE_GRADES)[rankIdx],
    letter: String.fromCharCode(65 + rankIdx)
  }
}