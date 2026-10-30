<template>
  <div class="engineering-art" aria-hidden="true">
    <svg viewBox="0 0 640 470" fill="none">
      <defs>
        <pattern id="engineering-grid" width="28" height="28" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="currentColor" opacity=".16" /></pattern>
      </defs>
      <rect width="640" height="470" fill="url(#engineering-grid)" />
      <circle cx="320" cy="235" r="174" stroke="currentColor" opacity=".08" />
      <circle cx="320" cy="235" r="134" stroke="currentColor" opacity=".1" stroke-dasharray="5 9" />
      <g class="circuit-lines" stroke="currentColor" stroke-width="2" opacity=".3">
        <path d="M270 174V108H170V64M320 174V72M370 174V118H496V76M258 218H144V152H75M258 258H100V334M270 296V366H180V411M320 296V410M370 296V354H476V407M382 218H505V171H563M382 258H542V314" />
        <g fill="currentColor"><circle cx="170" cy="64" r="4"/><circle cx="320" cy="72" r="4"/><circle cx="496" cy="76" r="4"/><circle cx="75" cy="152" r="4"/><circle cx="100" cy="334" r="4"/><circle cx="180" cy="411" r="4"/><circle cx="320" cy="410" r="4"/><circle cx="476" cy="407" r="4"/><circle cx="563" cy="171" r="4"/><circle cx="542" cy="314" r="4"/></g>
      </g>
      <rect x="240" y="156" width="160" height="160" rx="34" fill="currentColor" opacity=".06" />
      <rect x="256" y="172" width="128" height="128" rx="25" fill="var(--rail)" />
      <rect x="267" y="183" width="106" height="106" rx="19" stroke="#ffffff" stroke-opacity=".22" />
      <path d="M291 226L320 210L349 226L320 242L291 226Z" fill="#bde8df"/>
      <path d="M301 234V250C312 258 328 258 339 250V234M349 227V250" stroke="#bde8df" stroke-width="3" stroke-linecap="round"/>
      <g class="art-card art-card-one">
        <rect x="59" y="90" width="172" height="92" rx="16" fill="white" stroke="#dce7e5"/>
        <rect x="76" y="108" width="32" height="32" rx="10" fill="#e4f4ee"/>
        <path d="M85 124L90 129L100 118" stroke="#26836c" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
        <rect x="119" y="113" width="88" height="7" rx="3.5" fill="#3f5d6c"/>
        <rect x="119" y="129" width="59" height="5" rx="2.5" fill="#bacbd0"/>
        <rect x="76" y="154" width="131" height="5" rx="2.5" fill="#edf2f4"/>
        <rect x="76" y="154" width="90" height="5" rx="2.5" fill="#58a790"/>
      </g>
      <g class="art-card art-card-two">
        <rect x="421" y="191" width="162" height="99" rx="16" fill="white" stroke="#dce7e5"/>
        <circle cx="451" cy="220" r="13" fill="#fff0d6"/>
        <circle cx="451" cy="216" r="4" fill="#b2873c"/>
        <path d="M444 227C444 219 458 219 458 227" fill="#b2873c"/>
        <rect x="474" y="211" width="83" height="7" rx="3.5" fill="#3f5d6c"/>
        <rect x="474" y="226" width="48" height="5" rx="2.5" fill="#bacbd0"/>
        <rect x="439" y="251" width="124" height="6" rx="3" fill="#e8eff2"/>
        <rect x="439" y="265" width="91" height="6" rx="3" fill="#e8eff2"/>
      </g>
      <g class="art-card art-card-three">
        <rect x="116" y="318" width="152" height="64" rx="16" fill="white" stroke="#dce7e5"/>
        <rect x="132" y="334" width="32" height="32" rx="10" fill="#e9effa"/>
        <path d="M142 342H150L156 348V359H142V342ZM150 342V348H156" stroke="#5274b7" stroke-width="1.8" stroke-linejoin="round"/>
        <rect x="177" y="340" width="70" height="7" rx="3.5" fill="#3f5d6c"/>
        <rect x="177" y="355" width="49" height="5" rx="2.5" fill="#bacbd0"/>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.engineering-art{color:var(--brand-dark);width:100%;max-width:650px;margin:auto;pointer-events:none}
svg{display:block;width:100%;height:auto}
.art-card{animation:card-arrive .7s ease both;filter:drop-shadow(0 12px 15px #223d5110)}
.art-card-two{animation-delay:.12s}.art-card-three{animation-delay:.24s}
@keyframes card-arrive{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:translateY(0)}}
@media(prefers-reduced-motion:reduce){.art-card{animation:none}}
</style>
