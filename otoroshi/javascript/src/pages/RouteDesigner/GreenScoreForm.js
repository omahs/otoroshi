import React from 'react';
import {
  NgBooleanRenderer
} from '../../components/nginputs'

export default function GreenScoreForm(props) {
  const rootObject = props.rootValue?.green_score_rules;
  const sections = rootObject?.sections || [];

  const onChange = (checked, currentSectionIdx, currentRuleIdx) => {
    props.rootOnChange({
      ...props.rootValue,
      green_score_rules: {
        ...props.rootValue.green_score_rules,
        sections: sections.map((section, sectionIdx) => {
          if (currentSectionIdx !== sectionIdx)
            return section

          return {
            ...section,
            rules: section.rules.map((rule, ruleIdx) => {
              if (ruleIdx !== currentRuleIdx)
                return rule;


              console.log('changed')
              return {
                ...rule,
                enabled: checked
              }
            })
          }
        })
      }
    })
  }

  return <div>
    {sections.map(({ id, rules }, currentSectionIdx) => {
      return <div key={id} className='p-3'>
        <h4 className='mb-3' style={{ textTransform: 'capitalize' }}>{id}</h4>
        {rules.map(({ id, description, enabled, advice }, currentRuleIdx) => {
          return <div key={id}
            className='d-flex align-items-center'
            style={{
              cursor: 'pointer'
            }}
            onClick={e => {
              e.stopPropagation();
              onChange(!enabled, currentSectionIdx, currentRuleIdx)
            }}>
            <div className='flex'>
              <p className='offset-1 mb-0' style={{ fontWeight: 'bold' }}>{description}</p>
              <p className='offset-1'>{advice}</p>
            </div>
            <div style={{ minWidth: 52 }}>
              <NgBooleanRenderer
                value={enabled}
                onChange={checked => onChange(checked, currentSectionIdx, currentRuleIdx)}
                schema={{}}
                ngOptions={{
                  spread: true
                }}
              />
            </div>
          </div>
        })}
      </div>
    })}
  </div>
}