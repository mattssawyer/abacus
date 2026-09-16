<script setup lang="ts">
import { RouterLink } from 'vue-router'
import Avatar from 'primevue/avatar'
import Sidebar from 'primevue/sidebar'
import SidebarAside from 'primevue/sidebaraside'
import SidebarContent from 'primevue/sidebarcontent'
import SidebarHeader from 'primevue/sidebarheader'
import SidebarMenu from 'primevue/sidebarmenu'
import SidebarMenuButton from 'primevue/sidebarmenubutton'
import SidebarMenuItem from 'primevue/sidebarmenuitem'
import SidebarPanel from 'primevue/sidebarpanel'
import SidebarSpacer from 'primevue/sidebarspacer'
</script>

<template>
  <Sidebar id="app-sidebar" collapsible="none">
    <SidebarSpacer />
    <SidebarAside aria-label="Main navigation">
      <SidebarPanel>
        <SidebarHeader>
          <div class="sidebar-brand">
            <Avatar label="A" shape="circle" aria-hidden="true" />
            <span>Abacus</span>
          </div>
        </SidebarHeader>
        <SidebarContent>
          <nav aria-label="Dashboard navigation">
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton :as="RouterLink" to="/" :is-active="true" aria-current="page">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                    <path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z" />
                  </svg>
                  <span>Home</span>
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
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem;
}

.sidebar-brand span {
  font-size: 1.25rem;
  font-weight: 650;
  letter-spacing: -0.035em;
}
</style>
