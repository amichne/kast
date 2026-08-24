#!/usr/bin/env node
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const MODEL_START = '{_stage:`layouted`,projectId:`kast-public-architecture`'
const MODEL_END = 'manualLayouts:{}}'
const NODE_LAYOUT_FIELDS = ['x', 'y', 'width', 'height', 'labelBBox']
const EDGE_LAYOUT_FIELDS = ['points', 'labelBBox']

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function requireOwn(value, field, label) {
  requireCondition(
    Object.prototype.hasOwnProperty.call(value, field),
    `${label} is missing layout field ${field}`,
  )
}

function removeLayoutFields(value, fields, label) {
  for (const field of fields) {
    requireOwn(value, field, label)
    delete value[field]
  }
}

function extractLayoutedModel(bundle) {
  const start = bundle.indexOf(MODEL_START)
  requireCondition(start >= 0, 'LikeC4 bundle model start is missing')
  requireCondition(start === bundle.lastIndexOf(MODEL_START), 'LikeC4 bundle has multiple model starts')
  const endStart = bundle.indexOf(MODEL_END, start)
  requireCondition(endStart >= 0, 'LikeC4 bundle model end is missing')
  requireCondition(
    bundle.indexOf(MODEL_END, endStart + MODEL_END.length) < 0,
    'LikeC4 bundle has multiple model ends after the project model',
  )
  const expression = bundle.slice(start, endStart + MODEL_END.length)
  return structuredClone(
    runInNewContext(`(${expression})`, Object.create(null), { timeout: 1000 }),
  )
}

function canonicalize(model) {
  requireCondition(model._stage === 'layouted', 'LikeC4 project model is not layouted')
  requireCondition(
    model.projectId === 'kast-public-architecture',
    'LikeC4 bundle contains the wrong project model',
  )
  requireCondition(model.views && typeof model.views === 'object', 'LikeC4 project views are missing')
  model._stage = 'computed'

  for (const [viewId, view] of Object.entries(model.views)) {
    requireCondition(view._stage === 'layouted', `LikeC4 view ${viewId} is not layouted`)
    requireCondition(
      typeof view.hash === 'string' && view.hash.length > 0,
      `LikeC4 view ${viewId} has no generated hash`,
    )
    requireOwn(view, 'bounds', `LikeC4 view ${viewId}`)
    delete view.bounds
    view._stage = 'computed'

    if (view._type === 'dynamic') {
      requireOwn(view, 'sequenceLayout', `LikeC4 dynamic view ${viewId}`)
      delete view.sequenceLayout
    } else {
      requireCondition(
        !Object.prototype.hasOwnProperty.call(view, 'sequenceLayout'),
        `LikeC4 non-dynamic view ${viewId} has a sequence layout`,
      )
    }

    requireCondition(Array.isArray(view.nodes), `LikeC4 view ${viewId} nodes are missing`)
    requireCondition(Array.isArray(view.edges), `LikeC4 view ${viewId} edges are missing`)
    for (const node of view.nodes) {
      removeLayoutFields(node, NODE_LAYOUT_FIELDS, `LikeC4 view ${viewId} node ${node.id}`)
    }
    for (const edge of view.edges) {
      removeLayoutFields(edge, EDGE_LAYOUT_FIELDS, `LikeC4 view ${viewId} edge ${edge.id}`)
    }
  }
  return model
}

const bundlePath = process.argv[2]
requireCondition(bundlePath, 'usage: canonicalize_bundle_model.mjs <bundle>')
const bundle = readFileSync(bundlePath, 'utf8')
process.stdout.write(JSON.stringify(canonicalize(extractLayoutedModel(bundle))))
