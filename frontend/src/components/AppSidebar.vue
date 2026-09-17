<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { House } from '@lucide/vue'
import Sidebar from 'primevue/sidebar'
import SidebarAside from 'primevue/sidebaraside'
import SidebarContent from 'primevue/sidebarcontent'
import SidebarHeader from 'primevue/sidebarheader'
import SidebarMenu from 'primevue/sidebarmenu'
import SidebarMenuButton from 'primevue/sidebarmenubutton'
import SidebarMenuItem from 'primevue/sidebarmenuitem'
import SidebarPanel from 'primevue/sidebarpanel'
import SidebarSpacer from 'primevue/sidebarspacer'

const logoUrl = `${import.meta.env.BASE_URL}logo.svg`
</script>

<template>
  <Sidebar id="app-sidebar" collapsible="none" width="var(--app-sidebar-width)">
    <SidebarSpacer />
    <SidebarAside aria-label="Main navigation">
      <SidebarPanel class="sidebar-panel">
        <SidebarHeader>
          <div class="sidebar-brand" aria-label="Abacus">
            <img :src="logoUrl" alt="" width="32" height="32" />
            <span class="sidebar-label">Abacus</span>
          </div>
        </SidebarHeader>
        <SidebarContent>
          <nav aria-label="Dashboard navigation" class="sidebar-nav">
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  :as="RouterLink"
                  to="/"
                  :is-active="true"
                  aria-current="page"
                  aria-label="Home"
                >
                  <House :size="18" :stroke-width="1.75" aria-hidden="true" />
                  <span class="sidebar-label">Home</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </nav>
        </SidebarContent>
      </SidebarPanel>
    </SidebarAside>
  </Sidebar>
</template>

<style scoped>
.sidebar-panel {
  border-right: 1px solid var(--app-border);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.sidebar-brand span {
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.sidebar-nav {
  padding-inline: 0.5rem;
}

@media (max-width: 640px) {
  .sidebar-label {
    display: none;
  }

  .sidebar-brand {
    justify-content: center;
  }

  .sidebar-nav {
    padding-inline: 0;
  }
}
</style>
